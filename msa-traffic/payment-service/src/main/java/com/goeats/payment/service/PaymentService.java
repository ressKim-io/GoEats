package com.goeats.payment.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.entity.PaymentStatus;
import com.goeats.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * ★ Traffic MSA: Idempotent Payment Processing
 *
 * vs Basic MSA: Only checks existsByOrderId (no idempotencyKey)
 *
 * Double-payment prevention:
 * 1. Check by orderId → return existing payment if found
 * 2. Check by idempotencyKey → return existing payment if found
 * 3. Only create new payment if both checks pass
 *
 * This prevents:
 * - Duplicate Kafka event processing → same orderId
 * - Client retry with same idempotencyKey → same key
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public Payment processPayment(Long orderId, BigDecimal amount,
                                  String paymentMethod, String idempotencyKey) {
        // ★ Idempotent check 1: by orderId
        if (paymentRepository.existsByOrderId(orderId)) {
            log.warn("Payment already exists for order: {}", orderId);
            return paymentRepository.findByOrderId(orderId).orElseThrow();
        }

        // ★ Idempotent check 2: by idempotencyKey
        if (idempotencyKey != null) {
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> {
                        log.warn("Duplicate payment detected by idempotencyKey: {}", idempotencyKey);
                        return existing;
                    })
                    .orElseGet(() -> createPayment(orderId, amount, paymentMethod, idempotencyKey));
        }

        return createPayment(orderId, amount, paymentMethod, null);
    }

    private Payment createPayment(Long orderId, BigDecimal amount,
                                  String paymentMethod, String idempotencyKey) {
        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .idempotencyKey(idempotencyKey)
                .build();

        try {
            log.info("Processing payment: orderId={}, amount={}", orderId, amount);
            // Simulate PG call
            payment.complete();
        } catch (Exception e) {
            payment.fail();
            log.error("Payment failed: orderId={}", orderId, e);
        }

        return paymentRepository.save(payment);
    }

    @Transactional
    public void refund(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.refund();
        log.info("Payment refunded: orderId={}", orderId);
    }

    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
