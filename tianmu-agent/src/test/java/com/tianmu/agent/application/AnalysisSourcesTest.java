package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.preparation.TaskMeaning;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.core.AnalysisContract.Source.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisSourcesTest {
    final LocalDate day = LocalDate.of(2026, 11, 1);
    final Instant at = day.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    @Test void sourceSelectionIsFrozenForEveryNonemptyCombinationWithoutTreatingVideoIdAsVideoReading() {
        Map<String, Set<AnalysisContract.Source>> cases = new LinkedHashMap<>();
        cases.put("看看视频1001讲了什么", Set.of(VIDEO));
        cases.put("分析视频1001的评论", Set.of(COMMENT));
        cases.put("看看视频1001的评论", Set.of(COMMENT));
        cases.put("分析视频1001的弹幕", Set.of(DANMAKU));
        cases.put("结合视频内容分析视频1001的评论", Set.of(VIDEO, COMMENT));
        cases.put("结合画面分析视频1001的弹幕", Set.of(VIDEO, DANMAKU));
        cases.put("分析视频1001的评论和弹幕", Set.of(COMMENT, DANMAKU));
        cases.put("综合分析视频1001的视频内容、评论和弹幕", Set.of(VIDEO, COMMENT, DANMAKU));
        cases.forEach((query, expected) -> assertEquals(expected, TaskMeaning.defaults().resolve(query, day, at).reading().sources(), query));
    }
    @Test void exclusionsAndSingleSourceReportsDoNotSilentlyReadOtherSources() {
        for (String query : List.of("视频1001昨天的观众报告，不看视频，只分析评论和弹幕", "视频1001昨天的观众报告，不用读取画面，只分析评论、弹幕"))
            assertEquals(Set.of(COMMENT, DANMAKU), TaskMeaning.defaults().resolve(query, day, at).reading().sources());
        assertEquals(Set.of(COMMENT), TaskMeaning.defaults().resolve("视频1001的评论报告，不看视频和弹幕", day, at).reading().sources());
        assertEquals(Set.of(DANMAKU), TaskMeaning.defaults().resolve("视频1001的观众报告，只看弹幕", day, at).reading().sources());
        assertTrue(TaskMeaning.defaults().resolve("视频1001的评论报告", day, at).reading().reportRequired());
        for(String query:List.of("分析视频1001的评论，不需要报告","分析视频1001的弹幕，不用生成报告","分析视频1001的评论和弹幕，不需要报告")) {
            var plain=TaskMeaning.defaults().resolve(query,day,at);
            assertFalse(plain.reading().reportRequired(),query);assertFalse(AnalysisExecutionPolicy.permits("create_danmaku_report",plain));
        }
    }
    @Test void mixedReportFreezesPlaybackBoundsSeparatelyFromCollectionDates() {
        var spec=TaskMeaning.defaults().resolve("结合视频1001第2秒到第6秒的画面分析昨天的评论报告",day,at);
        assertEquals(Set.of(VIDEO,COMMENT),spec.reading().sources());
        assertEquals(2000L,spec.reading().videoStartMs());assertEquals(6000L,spec.reading().videoEndMs());
        assertEquals(day.minusDays(1).atStartOfDay(),((TaskConditions.Report)spec.typed()).query().start());
        assertEquals("AMBIGUOUS_ANALYSIS_SOURCES",assertThrows(AgentException.class,()->TaskMeaning.defaults().resolve("结合视频1001第6秒到第2秒的画面分析评论报告",day,at)).code());
    }
    @Test void followupReplacesOrAddsSourcesWithoutReinterpretingTheOriginalDateAndPageChangeDropsOldScope() {
        var meaning=TaskMeaning.defaults();
        var original=meaning.resolve("视频1001昨天的观众报告，不看视频，只分析评论和弹幕",day,at);
        var focus=new MemoryTypes.Focus("视频1001昨天的观众报告，不看视频，只分析评论和弹幕",UUID.randomUUID(),"1001",original);
        var memory=MemoryTypes.View.empty().withContext(new RequestContext("1001"),focus);
        var comments=meaning.resolve("这次只看评论",day.plusDays(1),at.plusSeconds(86400),memory);
        assertEquals(Set.of(COMMENT),comments.reading().sources());assertEquals(original.conditions(),comments.conditions());
        var next=memory.withContext(memory.environment(),new MemoryTypes.Focus(focus.request(),focus.sourceRunId(),"1001",comments));
        var withVideo=meaning.resolve("再加上视频",day.plusDays(1),at.plusSeconds(86400),next);
        assertEquals(Set.of(COMMENT,VIDEO),withVideo.reading().sources());assertEquals(original.conditions(),withVideo.conditions());
        var changed=meaning.resolve("这次只看评论",day,at,next.withContext(new RequestContext("1002"),focus));
        assertEquals(1002L,((TaskConditions.Report)changed.typed()).query().videoId());
        var onlyVideo=meaning.resolve("这次只看视频内容",day.plusDays(1),at.plusSeconds(86400),next);
        assertEquals(Set.of(VIDEO),onlyVideo.reading().sources());assertEquals(original.conditions(),onlyVideo.conditions());
        var restored=meaning.resolve("再加上评论",day.plusDays(1),at.plusSeconds(86400),next.withContext(next.environment(),new MemoryTypes.Focus(focus.request(),focus.sourceRunId(),"1001",onlyVideo)));
        assertEquals(Set.of(VIDEO,COMMENT),restored.reading().sources());assertEquals(original.conditions(),restored.conditions());
    }
    @Test void oldJsonRemainsReadableAndFrozenSourcesSurviveRoundTrip() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var current=TaskMeaning.defaults().resolve("视频1001的评论报告，不看视频和弹幕",day,at);
        var restored=json.readValue(json.writeValueAsBytes(current),TaskTypes.Specification.class);
        assertEquals(current.typed(),restored.typed());assertEquals(current.analysis(),restored.analysis());assertEquals(current.provenance(),restored.provenance());
        var old=json.valueToTree(current);((com.fasterxml.jackson.databind.node.ObjectNode)old).remove("analysis");
        var legacy=json.treeToValue(old,TaskTypes.Specification.class);assertNull(legacy.analysis());assertNotNull(legacy.reading());
        assertThrows(UnsupportedOperationException.class,()->current.analysis().sources().add(VIDEO));
    }
    @Test void ambiguousNegationDoesNotBecomeAnExpandedReadAndPolicyRejectsUnselectedTools() {
        assertEquals("AMBIGUOUS_ANALYSIS_SOURCES",assertThrows(AgentException.class,()->TaskMeaning.defaults().resolve("视频1001的观众报告，不是不看视频",day,at)).code());
        assertEquals("AMBIGUOUS_ANALYSIS_SOURCES",assertThrows(AgentException.class,()->TaskMeaning.defaults().resolve("只读取视频1001",day,at)).code());
        var spec=TaskMeaning.defaults().resolve("视频1001的评论报告，只分析评论",day,at);
        assertFalse(AnalysisExecutionPolicy.permits("analyze_video_content",spec));assertFalse(AnalysisExecutionPolicy.permits("query_danmaku",spec));
        assertTrue(AnalysisExecutionPolicy.permits("query_audience",spec));
        assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->AnalysisExecutionPolicy.requireSource(spec,VIDEO)).code());
    }
}
