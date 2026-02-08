package com.goeats.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ★ Circuit Breaker Fallback Controller
 *
 * When a downstream service circuit breaker opens, Gateway routes
 * to this fallback instead of returning a raw error.
 *
 * Provides graceful degradation with meaningful error messages.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> defaultFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "message", "Service is temporarily unavailable. Please try again later.",
                        "retryAfter", 30
                ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "gateway"));
    }
}
