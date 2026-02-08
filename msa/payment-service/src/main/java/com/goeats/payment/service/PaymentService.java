package com.goeats.payment.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * ★ MSA: PaymentService processes payments independently.
 * Triggered by Kafka events from OrderService, not by direct method calls.
 *
 * Compare with Monolithic: Called directly by OrderService.processPayment()
 * within the same @Transactional context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public Payment processPayment(Long orderId, BigDecimal amount, String paymentMethod) {
        if (paymentRepository.existsByOrderId(orderId)) {
            log.warn("Payment already exists for order: {}", orderId);
            return paymentRepository.findByOrderId(orderId).orElseThrow();
        }

        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .build();

        try {
            // Simulate PG call
            log.info("Processing payment: orderId={}, amount={}", orderId, amount);
            payment.complete();
        } catch (Exception e) {
            payment.fail();
            log.error("Payment failed: orderId={}", orderId, e);
        }

        return paymentRepository.save(payment);
    }

    public Payment getPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional
    public void refund(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.refund();
        log.info("Payment refunded: orderId={}", orderId);
    }
}
