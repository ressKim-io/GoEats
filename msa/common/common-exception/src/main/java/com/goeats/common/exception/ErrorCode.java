package com.goeats.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 에러 코드 열거형 - HTTP 상태 코드와 에러 메시지를 표준화하는 ErrorCode enum.
 *
 * <p>모든 비즈니스 예외는 이 enum의 값을 사용하여 일관된 에러 응답을 생성한다.
 * 새로운 에러 유형을 추가할 때 이 enum에 상수를 추가하기만 하면 된다.</p>
 *
 * <p>도메인별로 에러 코드를 그룹화하여 관리한다:
 * - 공통: INVALID_INPUT, ENTITY_NOT_FOUND, INTERNAL_ERROR, SERVICE_UNAVAILABLE
 * - 가게(Store): STORE_NOT_FOUND, STORE_CLOSED, MENU_NOT_FOUND
 * - 주문(Order): ORDER_NOT_FOUND, INVALID_ORDER_STATUS
 * - 결제(Payment): PAYMENT_FAILED, PAYMENT_NOT_FOUND
 * - 배달(Delivery): DELIVERY_NOT_FOUND, NO_AVAILABLE_RIDER</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 모든 도메인의 에러 코드가 하나의 enum에 있어도
 * 문제가 없다. MSA에서도 공통 모듈에 두는 이유는 서비스 간 에러 코드가 겹치지 않도록 하고,
 * 클라이언트(프론트엔드)가 모든 서비스의 에러를 동일한 방식으로 처리할 수 있게 하기 위함이다.
 * 단, 서비스가 많아지면 각 서비스에 로컬 ErrorCode를 두고 공통은 최소화하는 것이 좋다.</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // === 공통 에러 코드 ===
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Invalid input value"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "Entity not found"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),

    // === 가게(Store) 도메인 에러 코드 ===
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "Store not found"),
    STORE_CLOSED(HttpStatus.BAD_REQUEST, "Store is currently closed"),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "Menu not found"),

    // === 주문(Order) 도메인 에러 코드 ===
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "Invalid order status transition"),

    // === 결제(Payment) 도메인 에러 코드 ===
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "Payment processing failed"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),

    // === 배달(Delivery) 도메인 에러 코드 ===
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "Delivery not found"),
    NO_AVAILABLE_RIDER(HttpStatus.SERVICE_UNAVAILABLE, "No available rider");

    private final HttpStatus status;   // HTTP 응답 상태 코드 (400, 404, 500 등)
    private final String message;      // 클라이언트에 전달할 에러 메시지 (영문)
}
