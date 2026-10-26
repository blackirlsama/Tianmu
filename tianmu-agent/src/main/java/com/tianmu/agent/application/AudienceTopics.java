package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AudienceData.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only source IDs are classified by the model. Counts and stable membership IDs are computed here. */
public final class AudienceTopics {
    private AudienceTopics() {}
    public static List<Topic> parse(ObjectMapper json, String source, Overview overview, VideoContent.Facts video) {
        try {
            JsonNode nodes=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(source);
            if (!nodes.isArray() || nodes.size()>6) throw new IllegalArgumentException();
            Map<String,TextGroup> groups=new HashMap<>(); overview.corpus().forEach(g->groups.put(g.id(),g));
            List<Topic> result=new ArrayList<>(); Set<String> assigned=new HashSet<>();
            for (JsonNode node:nodes) {
                Set<String> fields=new HashSet<>();node.fieldNames().forEachRemaining(fields::add);
                if (!fields.containsAll(Set.of("title","observation","recommendation","sample_ids"))
                        || !Set.of("title","observation","recommendation","sample_ids","video_context","video_at_ms").containsAll(fields)) throw new IllegalArgumentException();
                String title=text(node,"title",80,false), observation=text(node,"observation",600,false), recommendation=text(node,"recommendation",600,true);
                JsonNode refs=node.path("sample_ids"); if(!refs.isArray() || refs.isEmpty() || refs.size()>120)throw new IllegalArgumentException();
                List<String> ids=new ArrayList<>();long count=0;
                for (JsonNode ref:refs) {
                    if(!ref.isTextual() || !groups.containsKey(ref.textValue()) || !assigned.add(ref.textValue()))throw new IllegalArgumentException();
                    ids.add(ref.textValue());count=Math.addExact(count,groups.get(ref.textValue()).count());
                }
                String context=node.has("video_context")?text(node,"video_context",600,true):"";
                List<Long> anchors=new ArrayList<>();
                if(node.has("video_at_ms")) {
                    if(!node.get("video_at_ms").isArray() || node.get("video_at_ms").size()>4)throw new IllegalArgumentException();
                    for(JsonNode at:node.get("video_at_ms")) {
                        if(!at.isIntegralNumber() || video==null || !video.sampledAtMs().contains(at.longValue()))throw new IllegalArgumentException();
                        anchors.add(at.longValue());
                    }
                }
                if(!context.isBlank() && (video==null || anchors.isEmpty()))throw new IllegalArgumentException();
                List<String> sorted=ids.stream().sorted().toList();
                String id="topic_"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join(",",sorted).getBytes(StandardCharsets.UTF_8))).substring(0,16);
                result.add(new Topic(id,title,observation,recommendation,ids,count,context,anchors));
            }
            return List.copyOf(result);
        } catch(Exception e) {
            throw new AgentException("INVALID_REPORT","Use at most 6 topics with title, observation, recommendation and sample_ids from audience.corpus. Each corpus ID belongs to at most one topic. Do not supply counts. Optional video_context requires video evidence and actual sampled video_at_ms anchors. Recommendation may be empty when unsupported.");
        }
    }
    private static String text(JsonNode node,String key,int max,boolean empty) {
        JsonNode v=node.path(key);if(!v.isTextual() || v.asText().length()>max || !empty && v.asText().isBlank())throw new IllegalArgumentException();return v.asText();
    }
}
