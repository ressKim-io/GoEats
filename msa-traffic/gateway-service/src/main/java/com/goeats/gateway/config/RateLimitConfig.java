package com.goeats.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * ★ Redis-based Rate Limiting Configuration
 *
 * KeyResolver determines HOW to group rate limit buckets:
 * - userKeyResolver: per-user rate limiting (via X-User-Id header)
 * - Falls back to IP-based if no user header present
 *
 * Rate limit values are configured per-route in application.yml:
 *   replenishRate: tokens added per second
 *   burstCapacity: max tokens (bucket size)
 *
 * Redis Token Bucket Algorithm:
 *   - Each user gets a token bucket in Redis
 *   - Requests consume tokens; tokens refill at replenishRate
 *   - If bucket empty → 429 Too Many Requests
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null) {
                return Mono.just(userId);
            }
            // Fallback to IP-based rate limiting
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "anonymous";
            return Mono.just(ip);
        };
    }
}
