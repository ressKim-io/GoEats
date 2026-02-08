package com.goeats.common.exception;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.concurrent.TimeoutException;

/**
 * 전역 예외 처리기 (Global Exception Handler)
 *
 * <p>모든 마이크로서비스에서 공유하는 중앙 집중식 예외 처리.
 * RFC 7807 ProblemDetail 형식으로 에러 응답을 통일.</p>
 *
 * <h3>처리하는 예외 유형</h3>
 * <ol>
 *   <li><b>BusinessException</b>: 도메인 규칙 위반 (주문 미발견, 결제 실패 등)</li>
 *   <li><b>RequestNotPermitted</b>: Resilience4j Rate Limiter 초과</li>
 *   <li><b>BulkheadFullException</b>: Resilience4j Bulkhead 동시 요청 초과</li>
 *   <li><b>CallNotPermittedException</b>: Resilience4j Circuit Breaker OPEN 상태</li>
 *   <li><b>TimeoutException</b>: Resilience4j TimeLimiter 타임아웃</li>
 * </ol>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 단일 ExceptionHandler로 도메인 예외만 처리하면 됨.
 * 트래픽 제어 관련 예외(Rate Limit, Circuit Breaker 등)는 존재하지 않음.
 * 단일 프로세스 내에서 메서드 호출이므로 네트워크 장애 자체가 없기 때문.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 BusinessException만 처리. MSA-Traffic에서는 Resilience4j 4개
 * 패턴(Rate Limiter, Bulkhead, Circuit Breaker, TimeLimiter)의 예외를 추가 처리.
 * 이를 통해 트래픽 과부하 상황에서도 클라이언트에 의미 있는 에러 메시지 전달.</p>
 *
 * <p>RFC 7807 ProblemDetail 응답 예시:</p>
 * <pre>
 *   {
 *     "type": "https://goeats.com/errors/rate_limit_exceeded",
 *     "status": 429,
 *     "detail": "Rate limit exceeded. Please try again later"
 *   }
 * </pre>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리.
     * ErrorCode에 정의된 HTTP 상태 코드와 메시지로 ProblemDetail 생성.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                errorCode.getStatus(), e.getMessage());
        // type URI: 에러 코드명을 소문자로 변환하여 에러 문서 URI 생성
        problem.setType(URI.create("https://goeats.com/errors/" +
                errorCode.name().toLowerCase()));
        return ResponseEntity.status(errorCode.getStatus()).body(problem);
    }

    // ★ Traffic MSA: Resilience4j Rate Limiter exceeded
    /**
     * Rate Limiter 초과 예외 처리.
     * 초당 허용 요청 수를 초과하면 429 Too Many Requests 반환.
     * 클라이언트는 이 응답을 받으면 일정 시간 후 재시도해야 함.
     */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ProblemDetail> handleRateLimitExceeded(RequestNotPermitted e) {
        log.warn("Rate limit exceeded: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMIT_EXCEEDED.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/rate_limit_exceeded"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
    }

    // ★ Traffic MSA: Resilience4j Bulkhead full (thread pool exhausted)
    /**
     * Bulkhead 동시 요청 초과 예외 처리.
     * 동시에 처리 가능한 요청 수(슬롯)가 모두 사용 중이면 503 반환.
     * Bulkhead는 서비스 간 장애 격리를 위해 동시 요청 수를 제한하는 패턴.
     */
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ProblemDetail> handleBulkheadFull(BulkheadFullException e) {
        log.warn("Bulkhead full: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.BULKHEAD_FULL.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/bulkhead_full"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    // ★ Traffic MSA: Resilience4j Circuit Breaker open
    /**
     * Circuit Breaker OPEN 상태 예외 처리.
     * 하위 서비스의 실패율이 임계치를 초과하면 Circuit Breaker가 열리고,
     * 일정 시간 동안 모든 요청을 즉시 차단하여 장애 전파를 방지.
     * OPEN → HALF_OPEN → CLOSED 순서로 자동 복구됨.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ProblemDetail> handleCircuitBreakerOpen(CallNotPermittedException e) {
        log.warn("Circuit breaker open: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.CIRCUIT_BREAKER_OPEN.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/circuit_breaker_open"));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    // ★ Traffic MSA: Timeout exceeded
    /**
     * 타임아웃 예외 처리.
     * Resilience4j TimeLimiter가 설정된 시간 내에 응답을 받지 못하면 504 반환.
     * 느린 하위 서비스가 상위 서비스의 스레드를 점유하는 것을 방지.
     */
    @ExceptionHandler(TimeoutException.class)
    public ResponseEntity<ProblemDetail> handleTimeout(TimeoutException e) {
        log.warn("Request timeout: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.GATEWAY_TIMEOUT, ErrorCode.REQUEST_TIMEOUT.getMessage());
        problem.setType(URI.create("https://goeats.com/errors/request_timeout"));
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(problem);
    }
}
