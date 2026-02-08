package com.goeats.payment.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 결제 결과를 Kafka로 발행하는 이벤트 퍼블리셔.
 *
 * <p>결제 처리 결과에 따라 두 가지 이벤트를 발행합니다:
 * <ul>
 *   <li>PaymentCompletedEvent → "payment-events" 토픽 → 배달 서비스가 수신하여 배달 생성</li>
 *   <li>PaymentFailedEvent → "payment-failed-events" 토픽 → 주문 서비스가 수신하여 주문 취소(보상)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: ApplicationEventPublisher로 같은 JVM 내 이벤트 발행 (동기/비동기)
 * - MSA: KafkaTemplate으로 메시지 브로커를 통해 다른 프로세스에 이벤트 전달
 *   → 서비스 간 네트워크 통신이므로 메시지 유실 가능성 존재
 *   → 프로덕션에서는 Outbox 패턴으로 이벤트 발행의 원자성 보장 필요</p>
 *
 * <p>kafkaTemplate.send()의 key 파라미터(orderId)는 Kafka 파티셔닝에 사용됩니다.
 * 같은 orderId의 이벤트는 항상 같은 파티션으로 전송되어 순서가 보장됩니다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 결제 성공 이벤트를 발행합니다.
     * 배달 서비스(delivery-service)가 이 이벤트를 수신하여 배달을 생성합니다.
     *
     * @param paymentId 생성된 결제 ID
     * @param orderId 주문 ID (Kafka 파티션 키로도 사용)
     * @param amount 결제 금액
     * @param paymentMethod 결제 수단 (CARD, CASH 등)
     */
    public void publishPaymentCompleted(Long paymentId, Long orderId,
                                         BigDecimal amount, String paymentMethod) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                paymentId, orderId, amount, paymentMethod);
        log.info("Publishing PaymentCompletedEvent: orderId={}", orderId);
        // orderId를 key로 사용 → 같은 주문의 이벤트는 같은 파티션에서 순서 보장
        kafkaTemplate.send("payment-events", String.valueOf(orderId), event);
    }

    /**
     * 결제 실패 이벤트를 발행합니다.
     * 주문 서비스(order-service)가 이 이벤트를 수신하여 주문을 취소(보상 트랜잭션)합니다.
     *
     * @param orderId 주문 ID
     * @param reason 실패 사유
     */
    public void publishPaymentFailed(Long orderId, String reason) {
        PaymentFailedEvent event = new PaymentFailedEvent(orderId, reason);
        log.info("Publishing PaymentFailedEvent: orderId={}, reason={}", orderId, reason);
        kafkaTemplate.send("payment-failed-events", String.valueOf(orderId), event);
    }
}
