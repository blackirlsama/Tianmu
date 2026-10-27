package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class CommentAnalysisReadiness implements QueryReadinessPolicy {
    public Optional<Clarification> validate(Input input) {
        if (!CommentAnalysisTask.requested(input.query())) return Optional.empty();
        try {
            DanmakuReportTask.parse(input.query(), input.businessDate(), input.requestedAt().atZone(ZoneId.of("Asia/Shanghai"))).checkTime(Instant.now());
            return Optional.empty();
        } catch (RuntimeException e) { return Optional.of(new Clarification(true, "请明确一个视频及有效起止时间；未指定时默认全量弹幕和评论。")); }
    }
}
