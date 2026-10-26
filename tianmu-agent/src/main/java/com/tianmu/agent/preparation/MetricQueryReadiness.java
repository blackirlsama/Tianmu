package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.DanmakuTarget;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Component
@org.springframework.core.annotation.Order(0)
public class MetricQueryReadiness implements QueryReadinessPolicy {
    public Optional<Clarification> validate(Input input) {
        if (DanmakuReportTask.requested(input.query()) || com.tianmu.agent.core.ConversationTopics.general(input.query())) return Optional.empty();
        if (!input.query().contains("弹幕")) return Optional.empty();
        if (DanmakuTarget.matcher(input.query()).results().count() != 1) return ask("请明确一个要查询的videoId，可写成视频ID或直播间ID。");
        var dateValues = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(input.query()).results().map(m -> m.group()).toList();
        if (dateValues.size() > 1 || !dateValues.isEmpty() && input.query().contains("昨天"))
            return ask("请一次明确一个查询日期，不要同时指定多个日期或相对日期。");
        LocalDate date;
        try {
            if (!dateValues.isEmpty()) date = LocalDate.parse(dateValues.get(0));
            else if (input.query().contains("昨天")) date = input.businessDate().minusDays(1);
            else return ask("请指定一个 YYYY-MM-DD 格式的日期，或使用“昨天”。");
        } catch (DateTimeException e) { return ask("日期无效，请提供 YYYY-MM-DD 格式的日期。"); }
        if (!date.isBefore(input.businessDate()) || date.isBefore(input.businessDate().minusDays(30)))
            return ask("请选择最近30天内已结束的自然日期。");
        return Optional.empty();
    }
    private Optional<Clarification> ask(String question) { return Optional.of(new Clarification(true, question)); }
}
