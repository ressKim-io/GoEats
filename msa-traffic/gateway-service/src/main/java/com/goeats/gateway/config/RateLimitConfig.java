package com.goeats.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Redis 기반 분산 Rate Limiting 설정 (Token Bucket Algorithm)
 *
 * <h3>역할</h3>
 * API 요청 속도를 제한하여 서버 과부하를 방지한다.
 * KeyResolver는 "누구 기준으로 요청 수를 세는가"를 결정한다.
 *
 * <h3>Token Bucket 알고리즘 (Redis에서 실행)</h3>
 * <pre>
 * 각 사용자(또는 IP)마다 Redis에 토큰 버킷이 생성됨:
 * - replenishRate: 초당 충전되는 토큰 수 (application.yml에서 설정)
 * - burstCapacity: 최대 토큰 수 (버킷 크기)
 * - 요청 1개 = 토큰 1개 소비
 * - 토큰이 0개이면 → 429 Too Many Requests 반환
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 Rate Limiting이 없어, 트래픽 폭증 시 서비스가 직접 부하를 받았다.
 * MSA-Traffic에서는 Gateway 레벨에서 Redis를 활용한 분산 Rate Limiting으로
 * 서버 인스턴스 수와 관계없이 일관된 속도 제한을 적용한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 인메모리 Rate Limiter(Guava, Bucket4j 등)로 충분하지만,
 * MSA에서는 여러 Gateway 인스턴스가 상태를 공유해야 하므로 Redis 기반 분산 Rate Limiting이 필수다.
 *
 * <h3>동작 흐름</h3>
 * <pre>
 * 클라이언트 → Gateway → [KeyResolver: 사용자 식별] → [Redis: 토큰 확인]
 *                                                      ├─ 토큰 있음 → 통과 → 다운스트림 서비스
 *                                                      └─ 토큰 없음 → 429 Too Many Requests
 * </pre>
 *
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

    /**
     * 사용자별 Rate Limit 키 결정 Bean
     *
     * X-User-Id 헤더가 있으면 → 사용자별 Rate Limiting (로그인 사용자)
     * X-User-Id 헤더가 없으면 → IP 기반 Rate Limiting (비로그인 사용자)
     *
     * X-User-Id 헤더는 JwtAuthGatewayFilter에서 JWT 검증 후 설정된다.
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // JWT 필터가 설정한 X-User-Id 헤더로 사용자 식별
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null) {
                return Mono.just(userId);
            }
            // Fallback to IP-based rate limiting
            // 비인증 요청(health check 등)은 IP 기반으로 제한
            String ip = exchange.getRequest().getRemoteAddress() != null
                    ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                    : "anonymous";
            return Mono.just(ip);
        };
    }
}
