package com.tianmu.agent.preparation;

import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

@Component
public final class CreatorInteractionTask implements TaskSpecificationResolver, QueryReadinessPolicy {
    public static boolean requested(String text) {
        if ((text.contains("报告") || text.contains("复盘")) && !text.contains("草稿") && !text.contains("智能评论") && !text.contains("智能弹幕")) return false;
        return text.matches("(?s).*(智能评论|智能弹幕|(?:生成|写|拟|起草|发送|发布).{0,20}(?:评论|弹幕)|(?:评论|弹幕).{0,12}草稿).*");
    }
    public static Specification parse(String text) {
        if (text.contains("评论") && text.contains("弹幕")) throw new IllegalArgumentException("请先选择生成评论还是弹幕草稿。");
        var video = VideoContentTask.parse(text);
        Map<String,Object> conditions = new HashMap<>(video.conditions());
        conditions.put("kind", text.contains("弹幕") ? "DANMAKU" : "COMMENT");
        Map<String,String> provenance = new HashMap<>(video.provenance()); provenance.put("kind", "user_input");
        return new Specification("creator.draft", conditions, provenance);
    }
    public Optional<Specification> resolve(String text, LocalDate date) {
        if (!requested(text)) return Optional.empty();
        try { return Optional.of(parse(text)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }
    public Optional<com.tianmu.agent.core.PreparationTypes.Clarification> validate(com.tianmu.agent.core.PreparationTypes.Input input) {
        if (!requested(input.query())) return Optional.empty();
        try { parse(input.query()); return Optional.empty(); }
        catch (IllegalArgumentException e) { return Optional.of(new com.tianmu.agent.core.PreparationTypes.Clarification(true, e.getMessage())); }
    }
}
