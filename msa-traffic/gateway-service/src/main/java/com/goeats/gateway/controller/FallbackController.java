package com.goeats.gateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Circuit Breaker Fallback 컨트롤러
 *
 * <h3>역할</h3>
 * 다운스트림 서비스의 Circuit Breaker가 Open 상태가 되었을 때,
 * Gateway가 원시 에러(500, Connection Refused 등) 대신 의미 있는 에러 응답을 반환한다.
 * 이를 통해 클라이언트에게 "잠시 후 재시도하세요"라는 메시지를 전달할 수 있다.
 *
 * <h3>Circuit Breaker 동작 흐름</h3>
 * <pre>
 * 1. 클라이언트 → Gateway → [CircuitBreaker: Closed] → 다운스트림 서비스 (정상)
 * 2. 다운스트림 장애 누적 → CircuitBreaker: Open 전환
 * 3. 클라이언트 → Gateway → [CircuitBreaker: Open] → FallbackController (우아한 에러)
 * 4. 일정 시간 후 → CircuitBreaker: Half-Open → 다운스트림 재시도
 * 5. 성공 시 → CircuitBreaker: Closed (복구)
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서는 서비스 간 직접 통신 시 Circuit Breaker만 있고, Gateway 레벨 Fallback이 없었다.
 * 클라이언트는 서비스 장애 시 원시 에러를 그대로 받았다.
 * MSA-Traffic에서는 Gateway에서 Fallback을 제공하여 사용자 경험을 개선한다(Graceful Degradation).
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 모든 기능이 하나의 프로세스에 있으므로 "부분 장애"라는 개념이 없다.
 * MSA에서는 특정 서비스만 장애가 발생할 수 있어, 장애 서비스에 대한 Fallback이 필요하다.
 *
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

    /**
     * 기본 Fallback 응답
     *
     * 모든 다운스트림 서비스 장애 시 이 엔드포인트가 호출된다.
     * - HTTP 503 (Service Unavailable) 반환
     * - retryAfter: 클라이언트에게 30초 후 재시도를 권장
     *
     * application.yml의 CircuitBreaker 설정에서 fallbackUri로 이 경로를 지정:
     * filters:
     *   - name: CircuitBreaker
     *     args:
     *       fallbackUri: forward:/fallback
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> defaultFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "success", false,
                        "message", "Service is temporarily unavailable. Please try again later.",
                        "retryAfter", 30
                ));
    }

    /**
     * Gateway 헬스 체크 엔드포인트
     *
     * Gateway 자체의 상태를 확인하는 용도.
     * 로드 밸런서(ALB/NLB)나 쿠버네티스 Health Probe에서 호출한다.
     * 인증 불필요 (JwtAuthGatewayFilter의 SKIP_PATHS에 포함).
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "gateway"));
    }
}
