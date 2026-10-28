package com.orangecode.tianmu.agentbridge;

import org.springframework.web.bind.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@ConditionalOnProperty(name="agent.bridge.enabled", havingValue="true")
@RequestMapping("/internal/agent/creator-publications")
public final class CreatorPublicationController {
    private final BusinessAuthentication authentication;
    private final CreatorPublication publications;
    public CreatorPublicationController(BusinessAuthentication authentication, CreatorPublication publications) {
        this.authentication = authentication; this.publications = publications;
    }
    @PostMapping
    public Map<String,Boolean> publish(@RequestHeader(value="Authorization",required=false) String auth,
                                     @RequestBody CreatorPublication.Batch body) {
        publications.publish(authentication.authenticate(auth), body);
        return Map.of("published",true);
    }
    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<Map<String,String>> failure(BridgeException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code",error.code()));
    }
}
