package com.goeats.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // Common
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "Invalid input value"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "Entity not found"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service temporarily unavailable"),

    // ★ Traffic MSA: Resilience4j error codes
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Please try again later"),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "Duplicate request detected"),
    BULKHEAD_FULL(HttpStatus.SERVICE_UNAVAILABLE, "Too many concurrent requests. Please try again later"),
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "Service circuit breaker is open"),
    REQUEST_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Request timed out"),

    // Store
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "Store not found"),
    STORE_CLOSED(HttpStatus.BAD_REQUEST, "Store is currently closed"),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "Menu not found"),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "Invalid order status transition"),

    // Payment
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "Payment processing failed"),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT, "Payment already processed for this order"),

    // Delivery
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "Delivery not found"),
    NO_AVAILABLE_RIDER(HttpStatus.SERVICE_UNAVAILABLE, "No available rider"),
    STALE_LOCK_DETECTED(HttpStatus.CONFLICT, "Stale lock detected via fencing token");

    private final HttpStatus status;
    private final String message;
}
