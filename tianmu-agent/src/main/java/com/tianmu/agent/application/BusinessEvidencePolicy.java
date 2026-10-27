package com.tianmu.agent.application;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.TaskConditions;
import com.tianmu.agent.runtime.EvidenceContext;
import java.time.*;
import java.util.*;

/** Business admission and evidence rules, independent of tool discovery and dispatch. */
public final class BusinessEvidencePolicy {
        public void checkCommentQuery(EvidenceContext run, com.tianmu.agent.core.DanmakuTypes.Query query) {
            run.checkpoint(); query.checkTime(Instant.now());
            if (!run.scope().videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "Video outside scope");
            if (run.task() == null || !"comments.analysis".equals(run.task().specification().type())
                    || !((TaskConditions.Report) run.task().specification().typed()).query().equals(query))
                throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Comments differ from prepared task");
        }
        public void checkQuery(EvidenceContext run, MetricQuery query) {
            run.checkpoint();
            if (run.task() != null && run.task().specification().type().equals("danmaku.report"))
                throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Use the prepared danmaku analysis query for report tasks");
            if (!run.scope().videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "Out-of-scope video");
            if (query.date().isBefore(run.businessDate().minusDays(30)) || !query.date().isBefore(run.businessDate()))
                throw new AgentException("DATE_OUT_OF_RANGE", "Only completed days within the previous 30 days are supported");
            if (run.task() != null && run.task().specification().type().equals("video.metric")) {
                var expected = (TaskConditions.Metric) run.task().specification().typed();
                if (expected.videoId() != query.videoId() || !expected.date().equals(query.date())
                        || !expected.metric().equals(query.metric())) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Tool query changed the prepared task conditions");
            }
        }
        public Evidence registerEvidence(EvidenceContext run, MetricData data) {
            run.checkpoint();
            if (!data.complete() || !"Asia/Shanghai".equals(data.timezone()) || data.value() < 0)
                throw new AgentException("INVALID_EVIDENCE", "Query adapter returned incomplete or inconsistent evidence");
            checkQuery(run, new MetricQuery(data.videoId(), data.date(), data.metric()));
            Evidence value = new Evidence("ev_" + UUID.randomUUID(), run.runId(), run.scope(), data);
            run.evidenceStore().put(value); return value;
        }
        public void checkVideoQuery(EvidenceContext run, long videoId) {
            checkVideoQuery(run, videoId, null, null);
        }
        public void checkVideoQuery(EvidenceContext run, long videoId, Long startMs, Long endMs) {
            run.checkpoint();
            if (!run.scope().videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Video outside scope");
            if (run.task() == null) return;
            var expected = run.task().specification().typed();
            if (expected instanceof TaskConditions.Video video) video.check(videoId, startMs, endMs);
            if (expected instanceof TaskConditions.Report report) {
                if (report.query().videoId() != videoId)
                    throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Video differs from prepared report");
            }
        }
        public Evidence registerVideoEvidence(EvidenceContext run, com.tianmu.agent.core.VideoContent.Facts facts) {
            checkVideoQuery(run, facts.videoId(), facts.startMs(), facts.endMs());
            Evidence value = new Evidence("ev_" + UUID.randomUUID(), run.runId(), run.scope(), null, null, facts);
            run.evidenceStore().put(value); return value;
        }
        public void checkDanmakuQuery(EvidenceContext run, com.tianmu.agent.core.DanmakuTypes.Query query) {
            run.checkpoint(); query.checkTime(Instant.now());
            if (!run.scope().videoIds().contains(query.videoId())) throw new AgentException("FORBIDDEN", "videoId is outside scope");
            if (run.task() == null || !Set.of("danmaku.report","danmaku.overview","audience.report").contains(run.task().specification().type())
                    || !((TaskConditions.Report) run.task().specification().typed()).query().equals(query))
                throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Analysis differs from the prepared task");
        }
        public Evidence registerDanmakuEvidence(EvidenceContext run, com.tianmu.agent.core.DanmakuTypes.Dataset data) {
            checkDanmakuQuery(run, data.query());
            if("audience.report".equals(run.task().specification().type()) != (data.sources()!=null))
                throw new AgentException("INVALID_EVIDENCE","Report sources differ from the prepared task");
            Evidence evidence = new Evidence("ev_" + UUID.randomUUID(), run.runId(), run.scope(), null, data);
            run.evidenceStore().put(evidence); return evidence;
        }
}
