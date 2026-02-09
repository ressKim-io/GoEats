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
 * 결제 처리 핵심 비즈니스 로직을 담당하는 서비스.
 *
 * <p>MSA에서 이 서비스는 Kafka 이벤트(OrderCreatedEvent)에 의해 트리거됩니다.
 * REST API를 통한 직접 호출이 아닌, 이벤트 기반 비동기 처리가 핵심입니다.</p>
 *
 * <p>처리 흐름:
 * 1. OrderEventListener가 Kafka에서 주문 이벤트 수신
 * 2. processPayment() 호출로 결제 처리
 * 3. 결과에 따라 PaymentEventPublisher가 성공/실패 이벤트 발행</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: OrderService에서 직접 this.paymentService.processPayment() 호출
 *   → 같은 @Transactional 안에서 실행되어 실패 시 전체 롤백
 * - MSA: 별도 프로세스에서 독립적으로 실행
 *   → 결제 실패 시 PaymentFailedEvent를 발행하여 보상 트랜잭션 유도
 *   → existsByOrderId()로 중복 결제를 방지 (멱등성 보장)</p>
 */

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

    /**
     * 결제를 처리합니다. Kafka 이벤트에 의해 트리거됩니다.
     *
     * existsByOrderId()로 중복 결제를 방지합니다.
     * Kafka 메시지가 재전송될 수 있으므로 멱등성(idempotency) 보장이 중요합니다.
     */
    @Transactional
    public Payment processPayment(Long orderId, BigDecimal amount, String paymentMethod) {
        // 멱등성 보장: 이미 결제가 존재하면 기존 결제를 반환 (Kafka 메시지 재전송 대비)
        if (paymentRepository.existsByOrderId(orderId)) {
            log.warn("Payment already exists for order: {}", orderId);
            return paymentRepository.findByOrderId(orderId).orElseThrow();
        }

        // 결제 엔티티 생성 (초기 상태: PENDING)
        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(amount)
                .paymentMethod(paymentMethod)
                .build();

        try {
            // Simulate PG call
            // 실제 프로덕션에서는 PaymentGateway.charge()를 호출하여 PG사와 통신
            log.info("Processing payment: orderId={}, amount={}", orderId, amount);
            payment.complete(); // 상태: PENDING → COMPLETED
        } catch (Exception e) {
            payment.fail(); // 상태: PENDING → FAILED
            log.error("Payment failed: orderId={}", orderId, e);
        }

        return paymentRepository.save(payment);
    }

    /**
     * 주문 ID로 결제 정보를 조회합니다.
     * PaymentController에서 REST API로 호출됩니다.
     */
    public Payment getPayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    /**
     * 결제를 환불 처리합니다. (Saga 보상 트랜잭션)
     * 주문 취소 시 호출되어 결제 상태를 COMPLETED → REFUNDED로 변경합니다.
     */
    @Transactional
    public void refund(Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.refund(); // 상태: COMPLETED → REFUNDED
        log.info("Payment refunded: orderId={}", orderId);
    }
}
