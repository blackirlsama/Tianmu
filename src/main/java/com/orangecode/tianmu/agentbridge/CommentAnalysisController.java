package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@ConditionalOnProperty(name="agent.bridge.enabled", havingValue="true")
@RequestMapping("/internal/agent")
public final class CommentAnalysisController {
    private final BusinessAuthentication authentication;
    private final BusinessCommentAnalysis comments;
    private final BusinessAudienceAnalysis audience;
    public CommentAnalysisController(BusinessAuthentication authentication, BusinessCommentAnalysis comments, BusinessAudienceAnalysis audience) {
        this.authentication = authentication; this.comments = comments; this.audience=audience;
    }
    @GetMapping("/audience-analysis")
    public BusinessAudienceAnalysis.Snapshot audience(@RequestHeader(value="Authorization",required=false)String auth,
            @RequestParam long videoId,@RequestParam LocalDateTime start,@RequestParam LocalDateTime end) {
        return audience.query(authentication.authenticate(auth),videoId,start,end);
    }
    @GetMapping("/comments-analysis")
    public BusinessCommentAnalysis.Dataset query(@RequestHeader(value="Authorization", required=false) String auth,
            @RequestParam long videoId, @RequestParam LocalDateTime start, @RequestParam LocalDateTime end) {
        return comments.query(authentication.authenticate(auth), videoId, start, end);
    }
    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<Map<String, String>> failure(BridgeException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code(), "message", error.code()));
    }
}
