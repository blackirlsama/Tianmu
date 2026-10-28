package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
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
        return matches.isEmpty() ? new Specification("unclassified", Map.of(), Map.of()) : matches.get(0);
    }
}
