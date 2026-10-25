package com.tianmu.agent.preparation;

import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

@Component
public final class VideoContentSpecificationResolver implements TaskSpecificationResolver {
    public Optional<Specification> resolve(String text, LocalDate date) {
        if (DanmakuReportTask.requested(text) || text.contains("弹幕") || com.tianmu.agent.core.ConversationTopics.general(text)
                || !text.matches("(?s).*(内容|画面|剧情|镜头|对白|讲了什么|发生了什么).*")) return Optional.empty();
        var ids=DanmakuTarget.matcher(text).results().toList();
        if (ids.size()!=1) return Optional.empty();
        try { return Optional.of(new Specification("video.content",Map.of("video_id",Long.parseLong(ids.get(0).group(1))),Map.of("video_id","user_input"))); }
        catch (NumberFormatException e) { return Optional.empty(); }
    }
}
