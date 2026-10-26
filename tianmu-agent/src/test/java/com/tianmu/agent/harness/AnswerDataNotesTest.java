package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.application.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnswerDataNotesTest {
    @Test void deliveryKeepsConversationalTextAndDerivesNotesOnlyFromOwnedEvidence() {
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = fixture.run();
            Instant asOf = Instant.parse("2026-10-25T07:19:48Z");
            var data = new MetricData(1001, LocalDate.of(2026, 10, 24), "bullet_count", 2400,
                    "v1", "mysql:bullet:retained-persisted-v1", asOf, "Asia/Shanghai", true, false);
            run.execution().evidence().put(new Evidence("ev_notes", run.id, run.owner, data));
            String text = "查到了，这个视频在10月24日共有2,400条弹幕。";
            fixture.execute(run, context -> new Decision(Kind.ANSWER, null,
                    new Answer(text, List.of("ev_notes"), List.of(new AnswerNote("fake", "untrusted", Instant.EPOCH, "UTC"))), null));
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            Answer answer = run.snapshot().answer();
            assertEquals(text, answer.text());
            assertEquals(1, answer.dataNotes().size());
            assertEquals(asOf, answer.dataNotes().get(0).updatedAt());
            assertTrue(answer.dataNotes().get(0).text().contains("不含已删除记录"));
            assertFalse(answer.dataNotes().get(0).text().contains("untrusted"));
        }
    }
    @Test void oldStoredAnswersRemainReadableAndNotesSurviveSerialization() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        assertTrue(json.readValue("{\"text\":\"old\",\"evidenceIds\":[\"ev_1\"]}", Answer.class).dataNotes().isEmpty());
        var answer = new Answer("new", List.of("ev_1"), List.of(new AnswerNote("统计范围", "保留记录", Instant.EPOCH, "Asia/Shanghai")));
        assertEquals(answer, json.readValue(json.writeValueAsString(answer), Answer.class));
    }
    @Test void videoCoverageNotesCannotBeOverriddenByModelMetadata() {
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = fixture.run(); Instant asOf = Instant.parse("2026-10-26T07:00:00Z");
            var facts = new VideoContent.Facts("video-content-v1", 1001, "source-v1", 2000, 6000, "scene",
                    List.of(new VideoContent.Moment(2500, "frame")), "", "SAMPLED_FRAMES", List.of(2500L, 3500L), "model", asOf);
            run.execution().evidence().put(new Evidence("video", run.id, run.owner, null, null, facts));
            fixture.execute(run, context -> new Decision(Kind.ANSWER, null,
                    new Answer("scene", List.of("video", "video"), List.of(new AnswerNote("fake", "watched everything", Instant.EPOCH, "UTC"))), null));
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            var notes = run.snapshot().answer().dataNotes();
            assertEquals(1, notes.size());
            assertTrue(notes.get(0).label().contains("2000–6000ms"));
            assertTrue(notes.get(0).text().contains("仅采样2帧"));
            assertTrue(notes.get(0).text().contains("未提供可用音频转写"));
            assertEquals(asOf, notes.get(0).updatedAt());
        }
    }
    @Test void numericEvidenceCannotSubstituteForRequestedVideoEvidence() {
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            var run = fixture.run();
            var data = new MetricData(1001, run.businessDate.minusDays(1), "bullet_count", 0,
                    "v1", "mysql:bullet:retained-persisted-v1", Instant.now(), "Asia/Shanghai", true, false);
            var specification = new TaskTypes.Specification("video.content", Map.of("video_id", 1001L), Map.of());
            assertThrows(AgentException.class, () -> EvidencePresentation.requireKind(specification,
                    List.of(new Evidence("metric", run.id, run.owner, data))));
        }
    }
}
