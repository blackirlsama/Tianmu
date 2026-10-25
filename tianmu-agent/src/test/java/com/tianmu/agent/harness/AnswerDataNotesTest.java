package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.*;
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
}
