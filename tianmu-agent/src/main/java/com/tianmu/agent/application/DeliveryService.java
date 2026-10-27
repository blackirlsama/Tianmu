package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.StopReason;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.FaqAnswer;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.harness.hook.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.TurnControl;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class DeliveryService {
    private final RuntimeHarness harness;
    public DeliveryService(RuntimeHarness harness) { this.harness = harness; }
    public void clarification(RunState run, String question) { clarification(run, question, reserve(run)); }
    public TurnControl.Reservation reserve(RunState run) {
        run.checkpoint();
        TurnControl.Reservation reservation = run.reserveFinalResponse();
        if (reservation == null) { run.checkpoint(); throw new AgentException("FINAL_RESPONSE_BUSY", "Final response already reserved"); }
        return reservation;
    }
    public void faq(RunState run, FaqAnswer faq) {
        TurnControl.Reservation reservation = reserve(run);
        harness.stage(run, "agent.deliver.faq", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER,
                com.tianmu.agent.harness.trace.TraceSemantics.faq(faq), () -> {
            com.tianmu.agent.harness.trace.TraceDetails.input(faq);
            Answer answer = harness.transform(run, TransformPoint.ANSWER, new Answer(faq.text(), List.of()));
            // Static FAQ answers do not make business data claims.
            if (!answer.evidenceIds().isEmpty()) throw new AgentException("INVALID_EVIDENCE", "FAQ cannot invent evidence");
            run.checkpoint();
            run.succeed(reservation, answer, StopReason.FAQ_ANSWER);
            return answer;
        });
    }
    public void clarification(RunState run, String text, TurnControl.Reservation reservation) {
        harness.stage(run, "agent.deliver", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER, Map.of(), () -> {
            com.tianmu.agent.harness.trace.TraceDetails.input(Map.of("clarification", text));
            String clarification = harness.transform(run, TransformPoint.CLARIFICATION, text);
            run.checkpoint();
            run.clarify(reservation, clarification);
            return clarification;
        });
    }
    public void answer(RunState run, Answer raw, TurnControl.Reservation reservation, StopReason reason) {
        if (run.task() != null && Set.of("danmaku.report","audience.report").contains(run.task().specification().type()) && reason != StopReason.TOOL_DELIVERY)
            throw new AgentException("REPORT_REQUIRED", "Create the requested report before delivering an answer");
        harness.stage(run, "agent.deliver", HookPoint.PRE_DELIVER, HookPoint.POST_DELIVER,
                Map.of("answer.evidence_count", raw == null ? 0 : raw.evidenceIds().size()), () -> {
            com.tianmu.agent.harness.trace.TraceDetails.input(raw);
            Answer answer = harness.transform(run, TransformPoint.ANSWER, raw);
            if (reason == StopReason.FINALIZED_ANSWER) answer = new Answer("【轮次已达上限，仅基于已获取证据收尾】\n" + answer.text(), answer.evidenceIds());
            validateAnswer(answer, run);
            // Notes are derived from validated, owned evidence rather than model-generated metadata.
            List<Evidence> evidence = answer.evidenceIds().stream().distinct().map(id -> run.execution().evidence().get(id)).toList();
            if (run.task() != null) EvidencePresentation.requireKind(run.task().specification(), evidence);
            for (Evidence value : evidence) if (value.comments() != null
                    && run.execution().commentOffset(value.id()) != value.comments().totalComments())
                throw new AgentException("FULL_ANALYSIS_INCOMPLETE", "尚未阅读全部评论，不能交付完整评论分析。");
            List<AnswerNote> notes = EvidencePresentation.notes(evidence);
            answer = new Answer(answer.text(), answer.evidenceIds(), notes);
            run.checkpoint();
            run.succeed(reservation, answer, reason);
            return answer;
        });
    }
    private void validateAnswer(Answer answer, RunState run) {
        boolean conversation = run.task() != null && "conversation".equals(run.task().specification().type());
        if (answer == null || answer.text() == null || answer.text().isBlank() || answer.text().length() > 8000
                || (!conversation && answer.evidenceIds().isEmpty()) || answer.evidenceIds().size() > 10)
            throw new AgentException("INVALID_ANSWER", "A bounded answer with evidence is required");
        for (String id : answer.evidenceIds()) {
            Evidence evidence = run.execution().evidence().get(id);
            if (evidence == null || !evidence.owner().equals(run.owner) || !evidence.runId().equals(run.id))
                throw new AgentException("INVALID_EVIDENCE", "Answer cites unavailable evidence");
        }
    }

}
