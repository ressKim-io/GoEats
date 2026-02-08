package com.goeats.payment.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.order.entity.Order;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★ Monolithic: PaymentService is called directly by OrderService
 * within the same @Transactional context. If payment fails,
 * the entire transaction (order + payment) is automatically rolled back.
 *
 * Compare with MSA: PaymentService is a separate microservice.
 * It receives OrderCreatedEvent via Kafka and processes payment independently.
 * On failure, it publishes PaymentFailedEvent for saga compensation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentService {

    private final PaymentRepository paymentRepository;

    @Transactional
    public Payment processPayment(Order order, String paymentMethod) {
        // Check if already paid
        paymentRepository.findByOrderId(order.getId()).ifPresent(p -> {
            throw new BusinessException(ErrorCode.ALREADY_PAID);
        });

        Payment payment = Payment.builder()
                .order(order)
                .amount(order.getTotalAmount())
                .paymentMethod(paymentMethod)
                .build();

        // Simulate PG (Payment Gateway) call
        try {
            simulatePgCall(payment);
            payment.complete();
            log.info("Payment completed for order: {}", order.getId());
        } catch (Exception e) {
            payment.fail();
            log.error("Payment failed for order: {}", order.getId(), e);
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, e.getMessage());
        }

        return paymentRepository.save(payment);
    }

    @Transactional
    public void refund(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.refund();
        log.info("Payment refunded for order: {}", orderId);
    }

    private void simulatePgCall(Payment payment) {
        // Simulate external PG API call
        log.info("Calling PG API: amount={}, method={}",
                payment.getAmount(), payment.getPaymentMethod());
    }
}
