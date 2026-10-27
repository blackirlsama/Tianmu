package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** The browser checks access before mounting the workspace; data endpoints still authorize independently. */
@RestController
@RequestMapping("/api/agent/videos")
public final class CreatorAccessController {
    private final IdentityPort identity;
    public CreatorAccessController(IdentityPort identity) { this.identity = identity; }
    @GetMapping("/{videoId}/access")
    public Map<String, Object> access(@PathVariable long videoId, @RequestHeader(value="Authorization", required=false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Login required");
        if (videoId <= 0) throw new AgentException("INVALID_REQUEST", "Invalid video ID");
        var scope = identity.forCreate(auth.substring(7), "视频" + videoId, null);
        if (!scope.videoIds().contains(videoId)) throw new AgentException("FORBIDDEN", "Only the video author may open the assistant");
        return Map.of("videoId", Long.toString(videoId), "allowed", true);
    }
}
