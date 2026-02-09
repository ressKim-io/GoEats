package com.goeats.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gateway 라우트 설정 (프로그래밍 방식)
 *
 * <h3>역할</h3>
 * 클라이언트 요청 경로(Path)에 따라 적절한 다운스트림 서비스로 라우팅한다.
 * YAML 기반 라우트 설정을 보충하는 프로그래밍 방식의 라우트를 정의한다.
 *
 * <h3>라우트 구성 요소 (각 라우트에 적용되는 필터 체인)</h3>
 * <pre>
 * 1. Path Predicate  - 어떤 경로의 요청을 이 라우트가 처리할지 결정
 * 2. RateLimiter     - Redis 기반 요청 속도 제한 (RateLimitConfig)
 * 3. CircuitBreaker  - 다운스트림 장애 감지 시 빠른 실패 + Fallback 응답
 * 4. StripPrefix     - /api/orders/** → /api/orders/** (접두사 제거 가능)
 * </pre>
 *
 * <h3>라우팅 흐름</h3>
 * <pre>
 * Client → Gateway(:8080) → [라우트 매칭] → [필터 체인 실행] → Service(:808x)
 *   예) /api/orders/** → order-service(:8081)
 *       /api/stores/** → store-service(:8082)
 *       /api/payments/** → payment-service(:8083)
 *       /api/deliveries/** → delivery-service(:8084)
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서는 Gateway가 없어 클라이언트가 각 서비스 포트에 직접 접근했다.
 * MSA-Traffic에서는 Gateway가 라우팅 + CircuitBreaker + RateLimiter를 조합하여
 * 하나의 진입점에서 트래픽을 통합 관리한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 모든 API가 하나의 서버에 있어 라우팅이 불필요했다.
 * MSA에서는 서비스가 분산되어 있으므로 Gateway의 라우팅이 필수다.
 *
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
    // YAML 설정이 주요 라우트를 담당하고, 이 클래스는 프로그래밍 방식 오버라이드용으로 예약됨

    /**
     * 커스텀 라우트 정의 (프로그래밍 방식)
     *
     * 현재는 Health Check 라우트만 정의:
     * - /health 경로 → Gateway 내부 FallbackController로 포워딩
     * - 인증 불필요 (JwtAuthGatewayFilter의 SKIP_PATHS에 포함)
     *
     * 필요 시 여기에 동적 라우트, 가중치 기반 라우팅(Canary),
     * A/B 테스트 라우팅 등을 프로그래밍 방식으로 추가할 수 있다.
     */
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                // Health check route (no auth required)
                // 헬스 체크 라우트: 인증 없이 Gateway 상태를 확인
                .route("health-check", r -> r
                        .path("/health")
                        .uri("forward:/fallback/health"))
                .build();
    }
}
