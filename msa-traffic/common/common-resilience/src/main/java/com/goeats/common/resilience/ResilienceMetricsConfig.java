package com.goeats.common.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.*;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

/**
 * ★ Prometheus Metrics Registration for Resilience4j
 *
 * Registers all Resilience4j pattern metrics with Micrometer/Prometheus:
 * - Circuit Breaker: state transitions, failure rates
 * - Retry: retry attempts count
 * - Rate Limiter: permitted/rejected calls
 * - Bulkhead: available concurrent calls
 *
 * Metrics available at: /actuator/prometheus
 * Example: resilience4j_circuitbreaker_state{name="storeService"} 0
 */
@Configuration
public class ResilienceMetricsConfig {

    public ResilienceMetricsConfig(
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
            RateLimiterRegistry rateLimiterRegistry,
            BulkheadRegistry bulkheadRegistry) {

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry)
                .bindTo(meterRegistry);
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry)
                .bindTo(meterRegistry);
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiterRegistry)
                .bindTo(meterRegistry);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry)
                .bindTo(meterRegistry);
    }
}
