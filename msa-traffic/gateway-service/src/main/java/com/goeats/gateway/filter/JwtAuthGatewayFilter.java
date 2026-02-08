package com.goeats.gateway.filter;

import com.goeats.common.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * ★ Gateway-level JWT Authentication Filter
 *
 * Flow:
 *   1. Extract Bearer token from Authorization header
 *   2. Validate JWT token
 *   3. Extract userId from token claims
 *   4. Add X-User-Id header to downstream request
 *   5. Downstream services trust X-User-Id (internal network only)
 *
 * Skip paths: /health, /actuator, /fallback (no auth required)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthGatewayFilter implements GlobalFilter, Ordered {

    private final JwtTokenProvider jwtTokenProvider;

    private static final List<String> SKIP_PATHS = List.of(
            "/health", "/actuator", "/fallback");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip authentication for public paths
        if (SKIP_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String token = resolveToken(exchange.getRequest().getHeaders());
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            log.warn("JWT validation failed for path: {}", path);
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        Long userId = jwtTokenProvider.getUserId(token);
        log.debug("JWT authenticated: userId={}, path={}", userId, path);

        // ★ Propagate userId as header to downstream services
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return -1; // Run before other filters
    }

    private String resolveToken(HttpHeaders headers) {
        String bearer = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
