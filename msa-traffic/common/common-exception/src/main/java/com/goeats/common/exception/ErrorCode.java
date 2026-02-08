package com.goeats.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 열거형 (Error Code Enum)
 *
 * <p>모든 마이크로서비스에서 공유하는 에러 코드 정의.
 * 각 에러 코드는 HTTP 상태 코드와 기본 에러 메시지를 포함.</p>
 *
 * <h3>에러 코드 분류</h3>
 * <ul>
 *   <li><b>Common</b>: 공통 에러 (입력값 오류, 엔티티 미발견, 서비스 불가)</li>
 *   <li><b>Traffic MSA (Resilience4j)</b>: 트래픽 제어 관련 에러</li>
 *   <li><b>도메인별</b>: Store, Order, Payment, Delivery 각 서비스 고유 에러</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 에러 코드가 단일 모듈 내에 존재하며, 트래픽 제어 관련 코드는 불필요.
 * MSA에서는 common 모듈로 분리하여 모든 서비스가 동일한 에러 코드를 공유.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에는 기본 도메인 에러만 존재. MSA-Traffic에서 추가된 에러 코드:</p>
 * <ul>
 *   <li><b>RATE_LIMIT_EXCEEDED</b>: 초당 요청 한도 초과 (429 Too Many Requests)</li>
 *   <li><b>DUPLICATE_REQUEST</b>: 멱등성 키 중복 감지 (409 Conflict)</li>
 *   <li><b>BULKHEAD_FULL</b>: 동시 요청 제한 초과 (503 Service Unavailable)</li>
 *   <li><b>CIRCUIT_BREAKER_OPEN</b>: 서킷 브레이커 열림 상태 (503 Service Unavailable)</li>
 *   <li><b>REQUEST_TIMEOUT</b>: 요청 타임아웃 (504 Gateway Timeout)</li>
 *   <li><b>STALE_LOCK_DETECTED</b>: Fencing Token으로 감지된 오래된 락 (409 Conflict)</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ── Common (공통 에러) ──
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Invalid input value"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "Entity not found"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),

    // ── ★ Traffic MSA: Resilience4j 트래픽 제어 에러 코드 ──
    // Rate Limiter가 초당 허용 요청 수를 초과했을 때
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Please try again later"),
    // Idempotency-Key 헤더로 감지된 중복 요청
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "Duplicate request detected"),
    // Bulkhead의 동시 실행 슬롯이 모두 사용 중일 때
    BULKHEAD_FULL(HttpStatus.SERVICE_UNAVAILABLE, "Too many concurrent requests. Please try again later"),
    // Circuit Breaker가 OPEN 상태여서 요청을 차단할 때
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "Service circuit breaker is open"),
    // TimeLimiter 타임아웃 초과
    REQUEST_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Request timed out"),

    // ── Store (가게 도메인) ──
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "Store not found"),
    STORE_CLOSED(HttpStatus.BAD_REQUEST, "Store is currently closed"),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "Menu not found"),

    // ── Order (주문 도메인) ──
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "Invalid order status transition"),

    // ── Payment (결제 도메인) ──
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "Payment processing failed"),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "Payment already processed for this order"),

    // ── Delivery (배달 도메인) ──
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "Delivery not found"),
    NO_AVAILABLE_RIDER(HttpStatus.SERVICE_UNAVAILABLE, "No available rider"),
    // Fencing Token 패턴: 동시 라이더 매칭 시 오래된 락을 감지
    STALE_LOCK_DETECTED(HttpStatus.CONFLICT, "Stale lock detected via fencing token");

    private final HttpStatus status;   // HTTP 응답 상태 코드
    private final String message;      // 기본 에러 메시지
}
