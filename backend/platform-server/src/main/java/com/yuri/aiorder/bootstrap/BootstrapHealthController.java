package com.yuri.aiorder.bootstrap;

import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BootstrapHealthController {

    @GetMapping("/api/bootstrap/health")
    public HealthResponse health() {
        return new HealthResponse("ok", Instant.now());
    }

    public record HealthResponse(String status, Instant checkedAt) {
    }
}
