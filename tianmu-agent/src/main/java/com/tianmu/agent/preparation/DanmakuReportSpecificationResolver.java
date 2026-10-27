package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import com.tianmu.agent.core.PreparationTypes.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class DanmakuReportSpecificationResolver implements TaskSpecificationResolver, QueryReadinessPolicy {
    public Optional<Specification> resolve(String text, LocalDate date) {
        return resolve(text, date, date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }
    public Optional<Specification> resolve(String text, LocalDate date, Instant requestedAt) {
        if (!DanmakuReportTask.requested(text)) return Optional.empty();
        try { return Optional.of(DanmakuReportTask.specification(DanmakuReportTask.parse(text, date, requestedAt.atZone(ZoneId.of("Asia/Shanghai"))))); }
        catch (IllegalArgumentException | DateTimeException e) { return Optional.empty(); }
    }
    public Optional<Clarification> validate(Input input) {
        if (!DanmakuReportTask.requested(input.query())) return Optional.empty();
        try { DanmakuReportTask.parse(input.query(), input.businessDate(), input.requestedAt().atZone(ZoneId.of("Asia/Shanghai"))).checkTime(Instant.now()); return Optional.empty(); }
        catch (RuntimeException e) { return Optional.of(new Clarification(true, "请明确一个视频ID和有效起止时间（最近30天）；不填时间时默认全量弹幕和评论。")); }
    }
}
