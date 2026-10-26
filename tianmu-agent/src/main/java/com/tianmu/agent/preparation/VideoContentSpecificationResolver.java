package com.tianmu.agent.preparation;

import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.TaskTypes.Specification;
import org.springframework.stereotype.Component;
import java.time.LocalDate;
import java.util.*;

@Component
public final class VideoContentSpecificationResolver implements TaskSpecificationResolver {
    public Optional<Specification> resolve(String text, LocalDate date) {
        if (!VideoContentTask.requested(text)) return Optional.empty();
        try { return Optional.of(VideoContentTask.parse(text)); }
        catch (IllegalArgumentException e) { return Optional.empty(); }
    }
}
