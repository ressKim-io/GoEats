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
 * Gateway 레벨 JWT 인증 필터 (GlobalFilter)
 *
 * <h3>역할</h3>
 * 모든 요청에 대해 JWT 토큰을 검증하고, 인증된 사용자 ID를
 * X-User-Id 헤더로 다운스트림 서비스에 전파한다.
 * 다운스트림 서비스는 내부 네트워크에서 이 헤더를 신뢰한다.
 *
 * <h3>인증 흐름</h3>
 * <pre>
 * 1. 클라이언트가 Authorization: Bearer {JWT} 헤더로 요청
 * 2. Gateway에서 JWT 토큰 추출 및 유효성 검증
 * 3. JWT에서 userId 클레임 추출
 * 4. 요청 헤더에 X-User-Id: {userId} 추가
 * 5. 다운스트림 서비스에 요청 전달
 * 6. 다운스트림 서비스는 X-User-Id 헤더를 읽어 사용자 식별
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서는 각 서비스가 개별적으로 JWT를 검증했다 → 중복 로직, 일관성 문제.
 * MSA-Traffic에서는 Gateway에서 한 번만 검증하고, 내부에서는 X-User-Id 헤더를 신뢰한다.
 * 이를 통해 인증 로직이 중앙화되고, 다운스트림 서비스의 인증 부담이 제거된다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 SecurityFilterChain에서 모든 요청을 인증했다.
 * MSA에서는 서비스가 분산되어 있어, Gateway에서 인증 후 사용자 정보를 전파하는 패턴을 사용한다.
 * 이 패턴은 "Edge Authentication" 또는 "Token Relay" 패턴이라고 불린다.
 *
 * <h3>SKIP_PATHS</h3>
 * /health, /actuator, /fallback 경로는 인증 없이 접근 가능하다.
 * 이 경로들은 모니터링, 헬스 체크, Fallback 용도로 사용된다.
 *
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

    // JWT 토큰 검증 및 클레임 추출을 담당하는 공통 모듈
    private final JwtTokenProvider jwtTokenProvider;

    // 인증을 건너뛸 공개 경로 목록
    private static final List<String> SKIP_PATHS = List.of(
            "/health", "/actuator", "/fallback");

    /**
     * 모든 요청에 대해 실행되는 필터 메서드 (GlobalFilter)
     *
     * GlobalFilter는 모든 라우트에 자동으로 적용되며,
     * Ordered 인터페이스의 getOrder()로 실행 순서를 제어한다.
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Skip authentication for public paths
        // 공개 경로는 인증 없이 통과 (헬스 체크, 모니터링, Fallback)
        if (SKIP_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        // JWT 토큰 추출 및 유효성 검증
        String token = resolveToken(exchange.getRequest().getHeaders());
        if (token == null || !jwtTokenProvider.validateToken(token)) {
            log.warn("JWT validation failed for path: {}", path);
            // 인증 실패 시 401 Unauthorized 반환하고 요청 체인 종료
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // JWT 클레임에서 userId 추출
        Long userId = jwtTokenProvider.getUserId(token);
        log.debug("JWT authenticated: userId={}, path={}", userId, path);

        // ★ Propagate userId as header to downstream services
        // ★ 다운스트림 서비스로 사용자 ID를 헤더로 전파
        // mutate()로 불변 요청 객체를 복사하여 헤더를 추가한다 (WebFlux 패턴)
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-User-Id", String.valueOf(userId))
                .build();

        // 변경된 요청으로 필터 체인 계속 진행
        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 필터 실행 순서: -1 (가장 먼저 실행)
     *
     * 음수 값일수록 먼저 실행된다.
     * 인증 필터는 다른 모든 필터(Rate Limiter, Circuit Breaker 등)보다
     * 먼저 실행되어야 하므로 가장 높은 우선순위를 가진다.
     */
    @Override
    public int getOrder() {
        return -1; // Run before other filters
    }

    /**
     * Authorization 헤더에서 Bearer 토큰 추출
     *
     * @param headers HTTP 요청 헤더
     * @return JWT 토큰 문자열 (Bearer 접두사 제거) 또는 null
     */
    private String resolveToken(HttpHeaders headers) {
        String bearer = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (bearer != null && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
