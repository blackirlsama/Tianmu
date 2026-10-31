package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public class TaskMeaning {
    private final List<TaskSpecificationResolver> resolvers;
    public TaskMeaning(List<TaskSpecificationResolver> resolvers) { this.resolvers = List.copyOf(resolvers); }
    public static TaskMeaning defaults() { return new TaskMeaning(List.of(new MetricTaskSpecificationResolver(), new DanmakuReportSpecificationResolver(), new VideoContentSpecificationResolver(), new CommentAnalysisSpecificationResolver(), new AudienceReportSpecificationResolver(), new CreatorInteractionTask())); }
    public Specification resolve(String text, LocalDate date) {
        return resolve(text, date, date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant());
    }
    public Specification resolve(String text, LocalDate date, java.time.Instant requestedAt) {
        List<Specification> matches = resolvers.stream().map(r -> r.resolve(text, date, requestedAt)).flatMap(Optional::stream).toList();
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous task specification resolvers");
        if (matches.isEmpty()) {
            if (com.tianmu.agent.core.DanmakuTarget.matcher(text).find() && AnalysisSourceSelection.mentions(text)
                    && !com.tianmu.agent.core.ConversationTopics.general(text)
                    && text.matches("(?s).*(?:只看|只读|仅看|单看|不看|不要|不用|排除|读取).*"))
                throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES", "请明确本次要分析视频内容、评论、弹幕中的哪些来源。");
            return new Specification("unclassified", Map.of(), Map.of());
        }
        Specification spec = matches.get(0);
        var baseline = com.tianmu.agent.core.AnalysisContract.legacy(spec.type());
        var reading = "creator.draft".equals(spec.type()) ? baseline : AnalysisSourceSelection.select(text, baseline);
        reading = playback(text, spec, reading);
        return selected(spec,reading,date,requestedAt);
    }
    private com.tianmu.agent.core.AnalysisContract playback(String text, Specification spec, com.tianmu.agent.core.AnalysisContract reading) {
        if(reading==null || !reading.includes(com.tianmu.agent.core.AnalysisContract.Source.VIDEO) || "creator.draft".equals(spec.type()))return reading;
        var typed=spec.typed();
        long id=typed instanceof com.tianmu.agent.core.TaskConditions.Video v?v.videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Report r?r.query().videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Metric m?m.videoId():0;
        if(id==0)return reading;
        try {
            var bounds=VideoContentTask.playbackBounds(text,id);
            return bounds.isEmpty()?reading:new com.tianmu.agent.core.AnalysisContract(reading.sources(),reading.reportRequired(),bounds.get().startMs(),bounds.get().endMs());
        } catch(IllegalArgumentException invalid) {
            throw new com.tianmu.agent.core.AgentException("AMBIGUOUS_ANALYSIS_SOURCES",invalid.getMessage());
        }
    }
    private Specification selected(Specification base,com.tianmu.agent.core.AnalysisContract reading,LocalDate date,java.time.Instant at) {
        if(reading==null || "creator.draft".equals(base.type()))return new Specification(base.type(),base.conditions(),base.provenance(),reading);
        var typed=base.typed();
        if(typed instanceof com.tianmu.agent.core.TaskConditions.Video video && reading.videoStartMs()==null)
            reading=new com.tianmu.agent.core.AnalysisContract(reading.sources(),reading.reportRequired(),video.startMs(),video.endMs());
        long id=typed instanceof com.tianmu.agent.core.TaskConditions.Video v?v.videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Metric m?m.videoId():typed instanceof com.tianmu.agent.core.TaskConditions.Report r?r.query().videoId():0;
        if(id==0)return new Specification(base.type(),base.conditions(),base.provenance(),reading);
        var texts=reading.textSources();
        String type;
        // Keep dormant feedback conditions when a follow-up temporarily selects video only.
        // Switching sources back must restore the same collection range, not parse a new date.
        if(texts.isEmpty())type=typed instanceof com.tianmu.agent.core.TaskConditions.Report || typed instanceof com.tianmu.agent.core.TaskConditions.Metric?base.type():"video.content";
        else if(texts.contains(com.tianmu.agent.core.AnalysisContract.Source.COMMENT))type=reading.reportRequired() || texts.size()==2?"audience.report":"comments.analysis";
        else if("video.metric".equals(base.type()))type="video.metric";
        else type=reading.reportRequired()?"danmaku.report":"danmaku.overview";
        if(type.equals(base.type()))return new Specification(type,base.conditions(),base.provenance(),reading);
        java.util.Map<String,Object> conditions;
        if(type.equals("video.content")) {
            var values=new java.util.LinkedHashMap<String,Object>();values.put("video_id",id);
            if(reading.videoStartMs()!=null){values.put("start_ms",reading.videoStartMs());values.put("end_ms",reading.videoEndMs());} conditions=values;
        } else if(typed instanceof com.tianmu.agent.core.TaskConditions.Report)conditions=base.conditions();
        else {
            var from=typed instanceof com.tianmu.agent.core.TaskConditions.Metric metric?metric.date().atStartOfDay():com.tianmu.agent.core.DanmakuTypes.ALL_TIME_START;
            var to=typed instanceof com.tianmu.agent.core.TaskConditions.Metric metric?metric.date().plusDays(1).atStartOfDay():at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime().withNano(0);
            conditions=Map.of("video_id",id,"start",from.toString(),"end",to.toString());
        }
        return new Specification(type,conditions,base.provenance(),reading);
    }
    public Specification resolve(String text,LocalDate date,java.time.Instant at,com.tianmu.agent.core.MemoryTypes.View memory) {
        var focus=memory.focus();
        if(focus!=null && !focus.request().equals(text) && AnalysisSourceSelection.mentions(text)
                && com.tianmu.agent.application.ConversationFocus.continuation(text)
                && (memory.environment()==null || java.util.Objects.equals(memory.environment().videoId(),focus.referenceVideoId()))) {
            var base=focus.task();
            if(base==null) {
                var anchor=memory.recent().stream().filter(m->m.runId().equals(focus.sourceRunId()) && "user".equals(m.role())).map(com.tianmu.agent.core.MemoryTypes.Message::at).findFirst().orElse(at);
                base=resolve(focus.request(),anchor.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate(),anchor,memory.environment());
            }
            if(base.reading()!=null) {
                var reading=AnalysisSourceSelection.select(text,base.reading());
                reading=playback(text,base,reading);
                return selected(base,reading,date,at);
            }
        }
        return resolve(text,date,at,memory.environment());
    }
    public Specification resolve(String text,LocalDate date,java.time.Instant at,com.tianmu.agent.core.RequestContext page) {
        var spec = resolve(com.tianmu.agent.application.RequestMessages.forParsing(text,page),date,at);
        if (page == null || com.tianmu.agent.core.DanmakuTarget.matcher(text).find() || !spec.conditions().containsKey("video_id")) return spec;
        Map<String,String> provenance = new HashMap<>(spec.provenance());provenance.put("video_id","page_context");
        return new Specification(spec.type(),spec.conditions(),provenance,spec.analysis());
    }
}
