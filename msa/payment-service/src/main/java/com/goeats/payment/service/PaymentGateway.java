package com.goeats.payment.service;

import java.math.BigDecimal;

/**
 * ★ MSA: Payment Gateway interface for external PG integration.
 * This abstraction allows swapping PG providers without changing service logic.
 */
public interface PaymentGateway {

    PaymentResult charge(String orderId, BigDecimal amount, String paymentMethod);

    PaymentResult refund(String paymentId, BigDecimal amount);

    record PaymentResult(boolean success, String transactionId, String message) {}
}
