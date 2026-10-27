package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.api.CreatorAccessController;
import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.query.CommentQueryPort;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommentAnalysisTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private DanmakuTypes.Query query() {
        var end = LocalDateTime.now(ZONE).minusHours(1).withNano(0);
        return new DanmakuTypes.Query(1001, end.minusDays(7), end);
    }
    private VideoComments.Comment comment(String id, String parent, String user, String text, boolean inPeriod, boolean author, boolean missing, DanmakuTypes.Query q) {
        return new VideoComments.Comment(id, parent, user, "昵称", text,
                (inPeriod ? q.start().plusHours(1) : q.start().minusDays(1)).atZone(ZONE).toInstant(), inPeriod, author, missing);
    }
    private VideoComments.Dataset dataset(int count) {
        var q = query(); List<VideoComments.Comment> comments = new ArrayList<>();
        comments.add(comment("1", null, "881", "作者先前的提问", false, true, false, q));
        for (int i = 0; i < count; i++) comments.add(comment(Integer.toString(i + 10), "1", "882", "相同回复", true, false, false, q));
        return new VideoComments.Dataset("video-comments-v1", q, count, count, count == 0 ? 0 : 1, comments, Instant.now(), "test");
    }
    private RunState run(VideoComments.Dataset data) {
        String text = "请分析视频1001的评论";
        RunState run = new RunState(new AccessScope("t", "881", Set.of(1001L)), text);
        var s = DanmakuReportTask.specification(data.query());
        var spec = new TaskTypes.Specification("comments.analysis", s.conditions(), s.provenance());
        run.prepared(new Result(Route.HARNESS, new PreparedRequest(text, text, Emotion.UNKNOWN, Set.of(), Set.of(), Intent.OTHER, spec), null, null));
        run.task(new TaskAssembler(new SkillCatalog(), new com.tianmu.agent.prompt.PromptCatalog(), new ToolCatalog()).assemble(run));
        return run;
    }
    private ToolDispatcher dispatcher(HarnessLifecycleTest.Fixture fixture, CommentQueryPort port) {
        var beans = new DefaultListableBeanFactory(); beans.registerSingleton("comments", port);
        var skills = new SkillCatalog();
        var handlers = List.<AgentTool>of(new ListSkillsTool(), new ReadSkillTool(), new ReadResultTool(),
                new QueryVideoCommentsTool(beans.getBeanProvider(CommentQueryPort.class)),
                new QueryAudienceTool(beans.getBeanProvider(com.tianmu.agent.query.AudienceQueryPort.class)), new QueryDanmakuTool((q, s, c) -> { throw new AssertionError(); }),
                new AnalyzeVideoContentTool(null, new ObjectMapper().findAndRegisterModules()),
                new CreateDanmakuReportTool(new ObjectMapper().findAndRegisterModules()), new QueryVideoMetricsTool((q, s) -> null));
        return new ToolDispatcher(skills, fixture.harness, new ToolRegistry(handlers, new ToolCatalog(), skills));
    }
    private Map<String, Object> args(VideoComments.Dataset data) {
        return new HashMap<>(Map.of("video_id", data.query().videoId(), "start", data.query().start().toString(), "end", data.query().end().toString()));
    }
    @Test void identicalRepliesKeepTheirIdsAndAncestorContextNeverInflatesTotals() throws Exception {
        var data = dataset(90);
        assertEquals(90, data.totalComments()); assertEquals(91, data.comments().size());
        assertEquals(90, data.comments().stream().filter(c -> c.text().equals("相同回复")).count());
        var first = data.page(0);
        assertEquals(40, ((List<?>)first.get("comments")).size());
        assertEquals(List.of(data.comments().get(0)), first.get("context"));
        assertFalse((Boolean)first.get("complete")); assertEquals(40, first.get("next_offset"));
        var json = new ObjectMapper().findAndRegisterModules();
        assertEquals(data, json.readValue(json.writeValueAsBytes(data), VideoComments.Dataset.class));
    }
    @Test void invalidCountsCyclesAndMissingParentFlagsCannotBecomeEvidence() {
        var data = dataset(2); var q = data.query();
        assertThrows(IllegalArgumentException.class, () -> new VideoComments.Dataset("video-comments-v1", q, 1, 1, 1, data.comments(), Instant.now(), "test"));
        var cyclic = List.of(comment("1", "2", "882", "a", true, false, false, q), comment("2", "1", "882", "b", true, false, false, q));
        assertThrows(IllegalArgumentException.class, () -> new VideoComments.Dataset("video-comments-v1", q, 2, 2, 1, cyclic, Instant.now(), "test"));
        var missing = List.of(comment("1", "2", "882", "回复已删除内容", true, false, false, q));
        assertThrows(IllegalArgumentException.class, () -> new VideoComments.Dataset("video-comments-v1", q, 1, 1, 1, missing, Instant.now(), "test"));
    }
    @Test void orderedPagesUseOneSnapshotAndPrematureDeliveryIsRejected() {
        var data = dataset(90); var calls = new AtomicInteger();
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var dispatcher = dispatcher(fixture, (q, scope, control) -> { calls.incrementAndGet(); return data; });
            var run = run(data); run.start();
            dispatcher.execute(new ToolCall("read_skill", Map.of("name", "comment-analysis")), run);
            var first = dispatcher.execute(new ToolCall("query_video_comments", args(data)), run);
            String id = first.evidenceId();
            var delivery = new DeliveryService(fixture.harness);
            assertEquals("FULL_ANALYSIS_INCOMPLETE", assertThrows(AgentException.class, () -> delivery.answer(run,
                    new Answer("已经读完", List.of(id)), delivery.reserve(run), StopReason.ANSWER)).code());
            var next = args(data); next.put("evidence_id", id); next.put("offset", 80);
            assertEquals("INVALID_COMMENT_OFFSET", assertThrows(AgentException.class, () -> dispatcher.execute(new ToolCall("query_video_comments", next), run)).code());
            next.put("offset", 40); dispatcher.execute(new ToolCall("query_video_comments", next), run);
            next.put("offset", 80); var last = dispatcher.execute(new ToolCall("query_video_comments", next), run);
            assertEquals(true, ((Map<?, ?>)last.data().get("page")).get("complete"));
            assertEquals(90, run.execution().commentOffset(id)); assertEquals(1, calls.get());
            run.cancel();
        }
    }
    @Test void wrongVideoAndChangedTimeRangeAreRejectedBeforeBusinessCalls() {
        var data = dataset(1); var calls = new AtomicInteger();
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var dispatcher = dispatcher(fixture, (q, scope, control) -> { calls.incrementAndGet(); return data; });
            var run = run(data); run.start(); dispatcher.execute(new ToolCall("read_skill", Map.of("name", "comment-analysis")), run);
            var arguments = args(data); arguments.put("video_id", 1002L);
            assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> dispatcher.execute(new ToolCall("query_video_comments", arguments), run)).code());
            arguments.put("video_id", 1001L); arguments.put("start", data.query().start().plusHours(1).toString());
            assertEquals("TASK_CONSTRAINT_VIOLATION", assertThrows(AgentException.class, () -> dispatcher.execute(new ToolCall("query_video_comments", arguments), run)).code());
            assertEquals(0, calls.get()); run.cancel();
        }
    }
    @Test void completeMultiPageAnalysisCanDeliverWithOwnedEvidence() {
        var data = dataset(90);
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var dispatcher = dispatcher(fixture, (q, scope, control) -> data);
            var run = run(data);
            new com.tianmu.agent.runtime.AgentRuntime(context -> {
                if (context.observations().isEmpty()) return Decision.tool("read_skill", Map.of("name", "comment-analysis"));
                var previous = context.observations().stream().filter(o -> o.tool().equals("query_video_comments")).reduce((a, b) -> b);
                var arguments = args(data);
                if (previous.isPresent()) {
                    var last = previous.get(); var page = (Map<?, ?>)last.data().get("page");
                    if (Boolean.TRUE.equals(page.get("complete"))) return Decision.answer("90条观众回复表达了相近观点。", List.of(last.evidenceId()));
                    arguments.put("evidence_id", last.evidenceId()); arguments.put("offset", page.get("next_offset"));
                }
                return Decision.tool("query_video_comments", arguments);
            }, dispatcher, fixture.harness).execute(run);
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertEquals(5, run.snapshot().steps());
            assertFalse(run.snapshot().answer().dataNotes().isEmpty());
        }
    }
    @Test void creatorAccessUsesAuthenticatedScopeAndKeepsLongIdsAsStrings() {
        var identity = mock(IdentityPort.class); var api = new CreatorAccessController(identity);
        when(identity.forCreate("token", "视频2114367144786530304", null)).thenReturn(new AccessScope("t", "881", Set.of(2114367144786530304L)));
        assertEquals("2114367144786530304", api.access(2114367144786530304L, "Bearer token").get("videoId"));
        when(identity.forCreate("token", "视频1002", null)).thenReturn(new AccessScope("t", "881", Set.of(1001L)));
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> api.access(1002, "Bearer token")).code());
        assertEquals("UNAUTHORIZED", assertThrows(AgentException.class, () -> api.access(1001, null)).code());
    }
    @Test void commentsUseTheExistingSevenDayParserWithoutCollidingWithVideoContent() {
        var now = Instant.now(); var date = now.atZone(ZONE).toLocalDate();
        var spec = TaskMeaning.defaults().resolve("请分析视频1001过去七天的评论，结合视频内容", date, now);
        assertEquals("comments.analysis", spec.type());
        var q = ((TaskConditions.Report)spec.typed()).query();
        assertEquals(Duration.ofDays(7), Duration.between(q.start(), q.end()));
        assertFalse(TaskCapabilities.toolsFor(spec.type(), new ToolCatalog().all().keySet()).contains("create_danmaku_report"));
        assertTrue(TaskMeaning.defaults().resolve("什么是评论运营建议", date, now).conditions().isEmpty());
        String scoped = RequestMessages.execution("请分析这个视频过去七天的评论", new RequestContext("1001"));
        assertEquals("comments.analysis", TaskMeaning.defaults().resolve(scoped, date, now).type());
    }
}
