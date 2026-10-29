package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.control.CallControl;
import io.opentelemetry.api.trace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

@Component
@EnableConfigurationProperties(VideoModelProperties.class)
public final class VideoAnalysisService {
    static final String FACT_FOCUS="提取通用画面事实：主体、场景、可见动作、构图及可见文字。按采样时间逐项记录，不围绕某个运营问题筛选，不提供建议。";
    private final VideoModelProperties config;
    private final ObjectProvider<VideoContentPort> ports;
    private final VideoContentCache cache;
    private final VisionModelPort vision;
    private final TranscriptionPort transcription;
    private final VideoAnalysisFlights flights;
    private final Tracer tracer;

    @org.springframework.beans.factory.annotation.Autowired
    public VideoAnalysisService(VideoModelProperties config, ObjectProvider<VideoContentPort> ports, VideoContentCache cache,
                                Tracer tracer, VisionModelPort vision, TranscriptionPort transcription, VideoAnalysisFlights flights) {
        this.config = config; this.ports = ports; this.cache = cache; this.tracer = tracer;
        this.vision = vision; this.transcription = transcription; this.flights = flights;
    }
    VideoAnalysisService(VideoModelProperties config, ObjectProvider<VideoContentPort> ports, VideoContentCache cache, ObjectMapper json, Tracer tracer, HttpClient http) {
        this(config, ports, cache, tracer, new VideoModelClient(config, json, http));
    }
    public VideoAnalysisService(VideoModelProperties config, ObjectProvider<VideoContentPort> ports, VideoContentCache cache, ObjectMapper json, Tracer tracer) {
        this(config, ports, cache, tracer, new VideoModelClient(config, json));
    }
    private VideoAnalysisService(VideoModelProperties config, ObjectProvider<VideoContentPort> ports, VideoContentCache cache, Tracer tracer, VideoModelClient model) {
        this(config, ports, cache, tracer, model, model, new VideoAnalysisFlights());
    }
    public VideoContent.Facts analyze(long videoId, Long start, Long end, String focus, AccessScope scope, CallControl control) {
        VideoAnalysisFlights.checkpoint(control);
        if (!scope.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Video outside prepared scope");
        if (!config.enabled()) throw new AgentException("VIDEO_CONTENT_UNAVAILABLE", "Video understanding is not configured; do not claim to have watched the video");
        VideoContentPort port = ports.getIfAvailable();
        if (port == null) throw new AgentException("VIDEO_CONTENT_UNAVAILABLE", "No authorized video content adapter");
        if ((start == null) != (end == null) || focus == null || focus.length() > 500) throw new AgentException("INVALID_ARGUMENTS", "Provide both playback bounds or neither");
        VideoContent.Source source = stage("video.source", () -> {
            TraceDetails.input(Map.of("video_id",videoId)); return port.source(videoId,scope,control);
        });
        VideoFactsValidator.source(source, videoId);
        long from = start == null ? 0 : start, to = end == null ? source.durationMs() : end;
        if (from < 0 || to <= from || to > source.durationMs() || start != null && to-from > 120_000)
            throw new AgentException("INVALID_ARGUMENTS", "Explicit intervals must be inside the video and at most 120 seconds");
        TraceAttributes.apply(Span.current(),Map.of("video.id",videoId,"video.source_version",source.sourceVersion(),"video.start_ms",from,"video.end_ms",to,"video.fact_strategy","generic_v4"));
        String key = hash(String.join("|", List.of(scope.tenantId(), scope.userId(), Long.toString(videoId), source.sourceVersion(),
                Long.toString(from), Long.toString(to), config.baseUrl().toString(), config.provider(), config.model(),
                config.transcriptionModel(), "video-facts-v4:sampled12:generic-v1",FACT_FOCUS).stream().map(VideoAnalysisService::hash).toList()));
        VideoContent.Facts prior = cache.get(key);
        TraceAttributes.apply(Span.current(),Map.of("video.cache_hit",prior != null));
        if (prior != null) { VideoAnalysisFlights.checkpoint(control); return prior; }
        VideoContent.Facts result = flights.run(key, control, () -> {
            VideoContent.Facts cached = cache.get(key);
            if (cached != null) return cached;
            return analyzeUncached(videoId, from, to, FACT_FOCUS, scope, source, port, key, control);
        });
        VideoAnalysisFlights.checkpoint(control);
        VideoContent.Source current = stage("video.source", () -> port.source(videoId, scope, control));
        if (!source.equals(current)) throw new AgentException("INVALID_EVIDENCE", "Video source changed during analysis");
        VideoAnalysisFlights.checkpoint(control);
        return result;
    }
    private VideoContent.Facts analyzeUncached(long videoId, long from, long to, String focus, AccessScope scope,
                                               VideoContent.Source source, VideoContentPort port, String key, CallControl control) {
        VideoContent.Media media = stage("video.extract", () -> {
            TraceDetails.input(Map.of("video_id",videoId,"source_version",source.sourceVersion(),"start_ms",from,"end_ms",to));
            return port.media(videoId,source.sourceVersion(),from,to,scope,control);
        });
        VideoFactsValidator.media(source, media, from, to);
        String transcript = "";
        if (!config.transcriptionModel().isBlank() && media.audioBase64()!=null && !media.audioBase64().isBlank())
            transcript = stage("video.model.transcription", () -> transcription.transcribe(media.audioBase64(),control));
        String speech = transcript;
        JsonNode response = stage("video.model.vision", () -> vision.understand(media,focus,speech,control));
        var facts = VideoFactsValidator.facts(media, response, transcript, config.model());
        VideoAnalysisFlights.checkpoint(control); cache.put(key, facts); return facts;
    }
    private <T> T stage(String name, Supplier<T> operation) {
        Span span=tracer.spanBuilder(name).startSpan(); TraceNode.begin(span,name);
        if (name.contains("model")) TraceAttributes.apply(span,Map.of("model.name",name.endsWith("transcription")?config.transcriptionModel():config.model(),"model.provider",config.provider(),"model.streaming",false));
        try (var scope=span.makeCurrent()) {
            T result=operation.get();
            if (result instanceof VideoContent.Source source) TraceDetails.output(source);
            if (result instanceof VideoContent.Media media) {
                TraceAttributes.apply(span,Map.of("video.frame_count",media.frames().size(),"video.audio_available",media.audioBase64()!=null&&!media.audioBase64().isBlank()));
                TraceDetails.output(Map.of("source",media.source(),"startMs",media.startMs(),"endMs",media.endMs(),"sampledAtMs",media.frames().stream().map(VideoContent.Frame::atMs).toList()));
            }
            TraceNode.success(span); return result;
        }
        catch (RuntimeException e) { TraceNode.failure(span,e); throw e; }
        finally { span.end(); }
    }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
