package com.goeats.order.config;

import feign.Request;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * ★ Traffic MSA: Feign Client Timeout Configuration
 *
 * Cascading timeout hierarchy:
 *   Gateway: 8s (outermost)
 *   Service: 5s (Resilience4j TimeLimiter)
 *   Feign Client: 3s connect + 5s read (innermost)
 *
 * This prevents Feign from holding threads longer than the
 * service-level timeout, avoiding thread pool exhaustion.
 */
@Configuration
public class FeignConfig {

    @Bean
    public Request.Options feignRequestOptions() {
        return new Request.Options(
                3, TimeUnit.SECONDS,   // connectTimeout
                5, TimeUnit.SECONDS,   // readTimeout
                true                    // followRedirects
        );
    }
}
