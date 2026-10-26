package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.application.RoomSummaryService;
import com.tianmu.agent.core.AgentException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/agent/rooms")
public final class RoomController {
    private final IdentityPort identity;private final RoomSummaryService summaries;
    public RoomController(IdentityPort identity,RoomSummaryService summaries){this.identity=identity;this.summaries=summaries;}
    @GetMapping("/{videoId}/summary")
    public org.springframework.http.ResponseEntity<RoomSummaryService.View> summary(@PathVariable long videoId,@RequestParam(required=false) LocalDateTime start,
                                          @RequestHeader(value="Authorization",required=false) String auth){
        if(auth==null || !auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Bearer authentication required");
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(summaries.get(videoId,start,identity.forCreate(auth.substring(7),"视频"+videoId,null)));
    }
}
