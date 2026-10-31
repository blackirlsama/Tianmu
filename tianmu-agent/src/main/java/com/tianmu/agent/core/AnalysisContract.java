package com.tianmu.agent.core;

import java.util.*;

/** Frozen reading/coverage requirements; source selection never grants access to another video. */
public record AnalysisContract(Set<Source> sources, boolean reportRequired, Long videoStartMs, Long videoEndMs) {
    public AnalysisContract(Set<Source> sources, boolean reportRequired) { this(sources,reportRequired,null,null); }
    public enum Source { VIDEO, COMMENT, DANMAKU }
    public AnalysisContract {
        sources = Set.copyOf(sources);
        if (sources.isEmpty()) throw new IllegalArgumentException("At least one analysis source is required");
        if((videoStartMs==null)!=(videoEndMs==null) || videoStartMs!=null && (videoStartMs<0 || videoEndMs<=videoStartMs || videoEndMs-videoStartMs>120_000))
            throw new IllegalArgumentException("Invalid frozen video bounds");
    }
    public boolean includes(Source source) { return sources.contains(source); }
    public Set<Source> textSources() {
        var selected = EnumSet.copyOf(sources); selected.remove(Source.VIDEO); return Set.copyOf(selected);
    }
    /** Older saved specifications remain readable without reinterpreting their original words. */
    public static AnalysisContract legacy(String type) {
        return switch (type) {
            case "audience.report" -> new AnalysisContract(EnumSet.allOf(Source.class), true);
            case "danmaku.report" -> new AnalysisContract(Set.of(Source.DANMAKU), true);
            case "danmaku.overview", "video.metric" -> new AnalysisContract(Set.of(Source.DANMAKU), false);
            case "comments.analysis" -> new AnalysisContract(Set.of(Source.COMMENT), false);
            case "video.content", "creator.draft" -> new AnalysisContract(Set.of(Source.VIDEO), false);
            default -> null;
        };
    }
}
