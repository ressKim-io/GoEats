package com.goeats.order.entity;

/**
 * ★ Saga State Machine Status
 *
 * STARTED → COMPENSATING → FAILED (payment failed, order cancelled)
 * STARTED → COMPLETED (happy path: payment + delivery succeeded)
 */
public enum SagaStatus {
    STARTED,
    COMPENSATING,
    COMPLETED,
    FAILED
}
