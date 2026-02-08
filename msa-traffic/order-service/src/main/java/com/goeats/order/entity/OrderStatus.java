package com.goeats.order.entity;

public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    PAID,
    PREPARING,
    DELIVERING,
    DELIVERED,
    CANCELLED
}
