package com.epsilon.welink.gateway.controller;

import com.epsilon.welink.im.service.IMService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class HealthController {

    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final IMService imService;

    public HealthController(IMService imService) {
        this.imService = imService;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        Map<String, Object> status = new HashMap<>();
        if (draining.get()) {
            status.put("status", "DOWN");
            status.put("reason", "draining");
            return ResponseEntity.status(503).body(status);
        }
        status.put("status", "UP");
        status.put("connections", imService.getOnlineCount());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/drain")
    public ResponseEntity<Map<String, Object>> drain() {
        draining.set(true);
        Map<String, Object> status = new HashMap<>();
        status.put("status", "DRAINING");
        status.put("connections", imService.getOnlineCount());
        status.put("message", "No new connections accepted, existing connections will drain");
        return ResponseEntity.ok(status);
    }

    @GetMapping("/livez")
    public ResponseEntity<Map<String, Object>> livez() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(status);
    }

    public boolean isDraining() {
        return draining.get();
    }
}
