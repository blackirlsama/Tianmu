package com.tianmu.agent.preparation;

import com.tianmu.agent.core.PreparationTypes.*;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public final class VideoContentReadiness implements QueryReadinessPolicy {
    public Optional<Clarification> validate(Input input) {
        if (!VideoContentTask.requested(input.query())) return Optional.empty();
        try { VideoContentTask.parse(input.query()); return Optional.empty(); }
        catch (IllegalArgumentException e) { return Optional.of(new Clarification(true, e.getMessage())); }
    }
}
