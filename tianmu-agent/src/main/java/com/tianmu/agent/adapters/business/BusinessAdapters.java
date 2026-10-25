package com.tianmu.agent.adapters.business;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.DanmakuTypes;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.BusinessQueryPort;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods=false)
@Profile("business")
@EnableConfigurationProperties(BusinessProperties.class)
public class BusinessAdapters {
    @Bean com.tianmu.agent.preparation.FaqPort businessFaq() {
        return new com.tianmu.agent.faq.ClasspathFaqPort(new org.springframework.core.io.ClassPathResource("faq/business.json"), null);
    }
    @Bean RemoteBusinessAdapter remoteBusinessAdapter(BusinessProperties properties, ObjectMapper json, ObjectProvider<RunRepository> repositories) {
        return new RemoteBusinessAdapter(properties, json, repositories);
    }
}

final class RemoteBusinessAdapter implements IdentityPort, BusinessQueryPort, com.tianmu.agent.query.DanmakuQueryPort, com.tianmu.agent.video.VideoContentPort {
    private record RawSample(String id, int bucket, String text, boolean truncated, Long playbackMs) {}
    private record AnalysisResponse(DanmakuTypes.Query query, long totalMessages, long uniqueSenders,
            List<DanmakuTypes.Bucket> buckets, List<RawSample> samples, Instant dataAsOf, String source, boolean demo) {}
    private final BusinessProperties config;
    private final ObjectMapper json;
    private final ObjectProvider<RunRepository> repositories;
    private final HttpClient http;
    private final BusinessCredentials credentials = new BusinessCredentials();
    RemoteBusinessAdapter(BusinessProperties config, ObjectMapper json, ObjectProvider<RunRepository> repositories) {
        this.config = config; this.json = json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.repositories = repositories;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.timeoutMillis())).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private JsonNode request(String method, String path, String token, Object body, CallControl control) {
        return request(method, path, token, body, control, 65536, config.timeoutMillis());
    }
    private JsonNode request(String method, String path, String token, Object body, CallControl control, int maxBytes, int timeoutMillis) {
        try {
            if (control != null) control.checkpoint();
            long millis = timeoutMillis;
            if (control != null) millis = Math.min(millis, Duration.between(Instant.now(), control.deadline()).toMillis());
            if (millis < 1) throw new AgentException("BUSINESS_TIMEOUT", "Business call deadline exceeded");
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMillis(millis))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, ignored -> new com.tianmu.agent.resource.BoundedHttpBody(maxBytes));
            try (var registration = control == null ? null : control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response = future.get(millis, TimeUnit.MILLISECONDS);
                if (control != null) control.checkpoint();
                if (response.statusCode() == 401) throw new AgentException("UNAUTHORIZED", "Business login is no longer valid");
                if (response.statusCode() == 403) throw new AgentException("FORBIDDEN", "Video is outside your current scope");
                if (response.statusCode() != 200 || response.body().length > maxBytes)
                    throw new AgentException("BUSINESS_UNAVAILABLE", "Business service response unavailable");
                return json.readTree(response.body());
            } catch (TimeoutException e) { future.cancel(true); throw new AgentException("BUSINESS_TIMEOUT", "Business service timed out"); }
            catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw new AgentException("CANCELLED", "Business call interrupted"); }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { if (control != null) control.checkpoint(); throw new AgentException("BUSINESS_UNAVAILABLE", "Business service unavailable"); }
    }
    @Override public AccessScope authenticate(String credential) {
        if (credential == null || credential.isBlank() || credential.length() > 8192)
            throw new AgentException("UNAUTHORIZED", "Business login is required");
        JsonNode principal = request("GET", "/internal/agent/principal", credential, null, null);
        String tenant = principal.path("tenantId").asText(), user = principal.path("userId").asText();
        if (!tenant.matches("[A-Za-z0-9_-]{1,64}") || !user.matches("[1-9][0-9]{0,18}"))
            throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid business principal");
        return new AccessScope(tenant, user, Set.of(), credentials.register(credential));
    }
    private Set<Long> allowed(AccessScope owner, Set<Long> requested, boolean requireAll) {
        if (requested.size() > 100) throw new AgentException("INVALID_REQUEST", "Too many target videos");
        if (requested.isEmpty()) return Set.of();
        JsonNode answer = request("POST", "/internal/agent/authorize", credentials.token(owner.authorizationRef()), Map.of("videoIds", requested), null);
        if (!answer.path("videoIds").isArray()) throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid authorization response");
        Set<Long> result = new HashSet<>();
        for (var value : answer.path("videoIds")) {
            long id;
            try {
                if (value.isIntegralNumber() && value.canConvertToLong()) id = value.longValue();
                else if (value.isTextual() && value.textValue().matches("[1-9][0-9]{0,18}")) id = Long.parseLong(value.textValue());
                else throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid authorization response"); }
            if (id <= 0 || !requested.contains(id)) throw new AgentException("BUSINESS_UNAVAILABLE", "Invalid authorization response");
            result.add(id);
        }
        if (requireAll && !result.containsAll(requested)) throw new AgentException("FORBIDDEN", "Video is outside your current scope");
        return Set.copyOf(result);
    }
    private AccessScope grant(AccessScope owner, Set<Long> resources, boolean requireAll) {
        return new AccessScope(owner.tenantId(), owner.userId(), allowed(owner, resources, requireAll), owner.authorizationRef());
    }
    @Override public AccessScope forCreate(String credential, String message, UUID reply) {
        AccessScope principal = authenticate(credential); Set<Long> resources = new HashSet<>();
        if (reply != null) resources.addAll(repositories.getObject().resourcesForRun(reply, principal));
        var matcher = DanmakuTarget.matcher(message);
        while (matcher.find()) try { resources.add(Long.parseLong(matcher.group(1))); }
        catch (NumberFormatException e) { throw new AgentException("INVALID_REQUEST", "Invalid video ID"); }
        return grant(principal, resources, true);
    }
    @Override public AccessScope forRun(String credential, UUID id) {
        AccessScope principal = authenticate(credential);
        return grant(principal, repositories.getObject().resourcesForRun(id, principal), true);
    }
    @Override public AccessScope forSession(String credential, UUID id, long before) {
        AccessScope principal = authenticate(credential);
        return grant(principal, repositories.getObject().resourcesForSession(id, principal, before), false);
    }
    @Override public MetricData query(MetricQuery query, AccessScope scope) { return query(query, scope, null); }
    @Override public MetricData query(MetricQuery query, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "Video is outside prepared scope");
        if (!"bullet_count".equals(query.metric())) throw new AgentException("METRIC_UNSUPPORTED", "Unsupported metric");
        JsonNode answer = request("GET", "/internal/agent/metrics?videoId=" + query.videoId() + "&date=" + query.date() + "&metric=bullet_count",
                credentials.token(scope.authorizationRef()), null, control);
        try { return json.treeToValue(answer, MetricData.class); }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Business metric schema mismatch"); }
    }
    @Override public com.tianmu.agent.core.DanmakuTypes.Dataset query(com.tianmu.agent.core.DanmakuTypes.Query query, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "videoId is outside prepared scope");
        query.checkTime(Instant.now());
        String start = java.net.URLEncoder.encode(query.start().toString(), java.nio.charset.StandardCharsets.UTF_8);
        String end = java.net.URLEncoder.encode(query.end().toString(), java.nio.charset.StandardCharsets.UTF_8);
        JsonNode answer = request("GET", "/internal/agent/danmaku-analysis?videoId=" + query.videoId() + "&start=" + start + "&end=" + end,
                credentials.token(scope.authorizationRef()), null, control);
        try {
            // The bridge returns raw snippets. Grouped occurrences belong to the Agent's tool/evidence model.
            AnalysisResponse raw = json.treeToValue(answer, AnalysisResponse.class);
            return new DanmakuTypes.Dataset(raw.query(), raw.totalMessages(), raw.uniqueSenders(), raw.buckets(),
                    raw.samples().stream().map(s -> new DanmakuTypes.Sample(s.id(), s.bucket(), s.text(), s.truncated(), List.of(new DanmakuTypes.SampleOccurrence(s.id(),s.bucket(),s.truncated(),s.playbackMs())))).toList(),
                    raw.dataAsOf(), raw.source(), raw.demo());
        }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Analysis schema mismatch"); }
    }
    @Override public com.tianmu.agent.video.VideoContent.Source source(long videoId, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Video outside prepared scope");
        JsonNode result = request("GET", "/internal/agent/video-source?videoId=" + videoId,
                credentials.token(scope.authorizationRef()), null, control);
        try { return json.treeToValue(result, com.tianmu.agent.video.VideoContent.Source.class); }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Video metadata schema mismatch"); }
    }
    @Override public com.tianmu.agent.video.VideoContent.Media media(long videoId, String version, long startMs, long endMs, AccessScope scope, CallControl control) {
        if (!scope.videoIds().contains(videoId) || !version.matches("[a-f0-9]{64}")) throw new AgentException("FORBIDDEN", "Video outside scope");
        JsonNode result = request("GET", "/internal/agent/video-content-media?videoId=" + videoId + "&version=" + version + "&startMs=" + startMs + "&endMs=" + endMs,
                credentials.token(scope.authorizationRef()), null, control, 8_000_000, 60_000);
        try { return json.treeToValue(result, com.tianmu.agent.video.VideoContent.Media.class); }
        catch (Exception e) { throw new AgentException("INVALID_EVIDENCE", "Video media schema mismatch"); }
    }

}
