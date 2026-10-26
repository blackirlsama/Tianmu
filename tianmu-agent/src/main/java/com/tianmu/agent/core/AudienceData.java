package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

/** Exact SQL statistics are separate from the bounded text corpus used for interpretation. */
public final class AudienceData {
    private AudienceData() {}
    public record Interaction(String userId, long messages, int activeDays, long distinctTexts, Instant lastAt) {
        public Interaction {
            if (userId == null || !userId.matches("[1-9][0-9]{0,18}") || messages < 1 || activeDays < 1 || activeDays > 31
                    || distinctTexts < 0 || distinctTexts > messages || lastAt == null) throw new IllegalArgumentException("Invalid interaction aggregate");
        }
    }
    public record Position(long startMs, long endMs, long count) {
        public Position { if (startMs < 0 || endMs <= startMs || count < 0) throw new IllegalArgumentException("Invalid playback aggregate"); }
    }
    public record TextGroup(String id, String text, long count, Long playbackMs, boolean truncated, List<Long> trend) {
        public TextGroup(String id,String text,long count,Long playbackMs,boolean truncated){this(id,text,count,playbackMs,truncated,List.of());}
        public TextGroup {
            if (id == null || !id.matches("[1-9][0-9]{0,18}") || text == null || text.length() > 320 || count < 1
                    || playbackMs != null && (playbackMs < 0 || playbackMs > 3_600_000)) throw new IllegalArgumentException("Invalid grouped text");
            trend=trend==null?List.of():List.copyOf(trend);
            if(!trend.isEmpty() && (trend.size()!=12 || trend.stream().anyMatch(n->n==null || n<0) || trend.stream().mapToLong(Long::longValue).reduce(0,Math::addExact)!=count))
                throw new IllegalArgumentException("Inconsistent grouped trend");
        }
        public TextGroup forModel(){
            int points=text.codePointCount(0,text.length());
            return points<=80?this:new TextGroup(id,text.substring(0,text.offsetByCodePoints(0,80)),count,playbackMs,true,trend);
        }
    }
    public record Overview(List<Interaction> interactions, List<Position> playback, long unknownPlayback,
                           List<TextGroup> corpus, long corpusMessages, long totalMessages) {
        public Overview {
            interactions = List.copyOf(interactions); playback = List.copyOf(playback); corpus = List.copyOf(corpus);
            if (interactions.size() > 20 || playback.size() > 120 || corpus.size() > 120 || unknownPlayback < 0
                    || corpusMessages < 0 || corpusMessages > totalMessages || unknownPlayback > totalMessages)
                throw new IllegalArgumentException("Invalid audience coverage");
            Set<String> ids = new HashSet<>(); long covered = 0, located = 0;
            for(Interaction fan:interactions)if(fan.messages()>totalMessages)throw new IllegalArgumentException("Interaction count exceeds total");
            for (TextGroup group : corpus) { if (!ids.add(group.id())) throw new IllegalArgumentException("Duplicate text group"); covered = Math.addExact(covered, group.count()); }
            long previousEnd=0;
            for (Position bin : playback) {
                if(bin.startMs()<previousEnd || bin.endMs()>3_600_000)throw new IllegalArgumentException("Overlapping playback bins");
                previousEnd=bin.endMs();located = Math.addExact(located, bin.count());
            }
            if (covered != corpusMessages || located + unknownPlayback != totalMessages)
                throw new IllegalArgumentException("Inconsistent audience totals");
        }
    }
    public record Topic(String id, String title, String observation, String recommendation, List<String> sampleIds,
                        long messages, String videoContext, List<Long> videoAtMs) {
        public Topic { sampleIds = List.copyOf(sampleIds); videoAtMs = List.copyOf(videoAtMs); }
    }
}
