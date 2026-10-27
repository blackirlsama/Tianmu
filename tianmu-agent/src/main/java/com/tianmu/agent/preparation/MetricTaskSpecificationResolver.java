package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import com.tianmu.agent.core.DanmakuTarget;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class MetricTaskSpecificationResolver implements TaskSpecificationResolver {
    public Optional<Specification> resolve(String original, LocalDate businessDate) {
        return resolve(original,businessDate,businessDate.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }
    public Optional<Specification> resolve(String original, LocalDate businessDate, Instant requestedAt) {
        if (AudienceReportTask.requested(original) || DanmakuReportTask.requested(original) || com.tianmu.agent.core.ConversationTopics.general(original)) return Optional.empty();
        var ids = DanmakuTarget.matcher(original).results().map(m -> m.group(1)).toList();
        var dates = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(original).results().map(m -> m.group()).toList();
        if(original.contains("弹幕") && ids.size()==1 && (dates.isEmpty() && !original.contains("昨天")
                || original.matches("(?s).*\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}.*"))) {
            try{
                var spec=DanmakuReportTask.specification(DanmakuReportTask.parse(original,businessDate,requestedAt.atZone(ZoneId.of("Asia/Shanghai"))));
                return Optional.of(new Specification("danmaku.overview",spec.conditions(),spec.provenance()));
            }catch(RuntimeException e){return Optional.empty();}
        }
        if (original.contains("弹幕") && ids.size() == 1 && (dates.size() == 1 && !original.contains("昨天") || dates.isEmpty() && original.contains("昨天"))) {
            try {
                long video = Long.parseLong(ids.get(0));
                String date = dates.isEmpty() ? businessDate.minusDays(1).toString() : LocalDate.parse(dates.get(0)).toString();
                return Optional.of(new Specification("video.metric", Map.of("video_id", video, "date", date, "metric", "bullet_count"),
                        Map.of("video_id", "user_input", "date", dates.isEmpty() ? "relative_date@" + businessDate : "user_input", "metric", "user_input")));
            } catch (IllegalArgumentException | DateTimeException ignored) {}
        }
        return Optional.empty();
    }
}
