package com.goeats.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ★ Gateway Route Configuration (Programmatic)
 *
 * Supplements YAML-based route config with programmatic circuit breaker
 * and rate limiter filters per service.
 *
 * Route hierarchy:
 *   Client → Gateway(:8080) → Service(:808x)
 *
 * Each route has:
 *   1. Path predicate
 *   2. RequestRateLimiter filter (Redis-backed)
 *   3. CircuitBreaker filter with fallback
 *   4. StripPrefix if needed
 */
@Configuration
public class RouteConfig {

    // YAML config handles routes; this class reserved for programmatic overrides
    // See application.yml for route definitions

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // Health check route (no auth required)
                .route("health-check", r -> r
                        .path("/health")
                        .uri("forward:/fallback/health"))
                .build();
    }
}
