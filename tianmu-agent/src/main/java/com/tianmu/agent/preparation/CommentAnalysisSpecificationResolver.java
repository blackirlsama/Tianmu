package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class CommentAnalysisSpecificationResolver implements TaskSpecificationResolver {
    public Optional<Specification> resolve(String text, LocalDate date) {
        return resolve(text, date, date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }
    public Optional<Specification> resolve(String text, LocalDate date, Instant requestedAt) {
        if (!CommentAnalysisTask.requested(text)) return Optional.empty();
        try {
            var query = DanmakuReportTask.parse(text, date, requestedAt.atZone(ZoneId.of("Asia/Shanghai")));
            var spec = DanmakuReportTask.specification(query);
            return Optional.of(new Specification("comments.analysis", spec.conditions(), spec.provenance()));
        } catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
