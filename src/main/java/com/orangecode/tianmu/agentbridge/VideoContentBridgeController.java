package com.orangecode.tianmu.agentbridge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="agent.bridge.enabled", havingValue="true")
@RequestMapping("/internal/agent")
public final class VideoContentBridgeController {
    private final BusinessAuthentication auth;
    private final BusinessVideoContent video;
    public VideoContentBridgeController(BusinessAuthentication auth, BusinessVideoContent video) { this.auth = auth; this.video = video; }
    @GetMapping("/video-source")
    public BusinessVideoContent.Source source(@RequestHeader(value="Authorization",required=false) String token, @RequestParam long videoId) {
        return video.source(auth.authenticate(token), videoId);
    }
    @GetMapping("/video-content-media")
    public BusinessVideoContent.Media media(@RequestHeader(value="Authorization",required=false) String token, @RequestParam long videoId,
            @RequestParam String version, @RequestParam long startMs, @RequestParam long endMs) {
        return video.sample(auth.authenticate(token), videoId, version, startMs, endMs);
    }
    @ExceptionHandler(BridgeException.class)
    public org.springframework.http.ResponseEntity<java.util.Map<String,String>> failure(BridgeException e) {
        return org.springframework.http.ResponseEntity.status(e.status()).body(java.util.Map.of("code",e.code()));
    }
}
