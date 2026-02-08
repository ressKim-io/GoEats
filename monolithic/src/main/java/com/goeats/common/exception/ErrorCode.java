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
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"),

    // User
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "Email already exists"),

    // Store
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "Store not found"),
    STORE_CLOSED(HttpStatus.BAD_REQUEST, "Store is currently closed"),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "Menu not found"),

    // Order
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "Order not found"),
    INVALID_ORDER_STATUS(HttpStatus.BAD_REQUEST, "Invalid order status transition"),
    INSUFFICIENT_STOCK(HttpStatus.BAD_REQUEST, "Insufficient stock"),

    // Payment
    PAYMENT_FAILED(HttpStatus.BAD_REQUEST, "Payment processing failed"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Payment not found"),
    ALREADY_PAID(HttpStatus.CONFLICT, "Order already paid"),

    // Delivery
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "Delivery not found"),
    NO_AVAILABLE_RIDER(HttpStatus.SERVICE_UNAVAILABLE, "No available rider");

    private final HttpStatus status;
    private final String message;
}
