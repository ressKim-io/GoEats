package com.goeats.payment.event;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 주문 이벤트를 수신하는 Kafka 리스너.
 *
 * <p>MSA Saga 패턴의 핵심 구성요소입니다.
 * 주문 서비스가 발행한 OrderCreatedEvent를 수신하여 결제 처리를 시작하고,
 * 결제 결과에 따라 PaymentCompletedEvent 또는 PaymentFailedEvent를 발행합니다.</p>
 *
 * <p>이벤트 흐름:
 * OrderService → [Kafka: order-events] → 여기서 수신 → PaymentService.processPayment()
 * → 결과에 따라 [Kafka: payment-events] 또는 [Kafka: payment-failed-events] 발행</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: orderService.createOrder() 내에서 paymentService.processPayment()를 직접 호출
 *   → 하나의 @Transactional로 원자성 보장
 * - MSA: Kafka를 통한 비동기 통신으로 느슨한 결합 실현
 *   → 결제 서비스가 다운되어도 이벤트가 Kafka에 보관되어 나중에 처리 가능
 *   → 대신 최종 일관성(Eventual Consistency)만 보장됨</p>
 */

/**
 * ★ MSA: Kafka listener that triggers payment processing.
 *
 * This is the key difference from Monolithic:
 * - Monolithic: orderService directly calls paymentService.processPayment()
 * - MSA: OrderService publishes OrderCreatedEvent → PaymentService listens here
 *
 * This enables:
 * - Loose coupling between services
 * - Independent scaling of payment processing
 * - Resilience (if payment is down, events are queued in Kafka)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final PaymentService paymentService;
    private final PaymentEventPublisher eventPublisher;

    /**
     * Kafka "order-events" 토픽에서 주문 생성 이벤트를 수신합니다.
     *
     * groupId = "payment-service"는 이 서비스의 컨슈머 그룹 ID입니다.
     * 같은 그룹 내 여러 인스턴스가 있으면 Kafka가 파티션을 분배하여
     * 하나의 메시지는 그룹 내 하나의 인스턴스만 처리합니다.
     */
    @KafkaListener(topics = "order-events", groupId = "payment-service")
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent: orderId={}", event.orderId());

        try {
            // 결제 처리 실행 - Kafka 이벤트로 트리거된 비동기 처리
            Payment payment = paymentService.processPayment(
                    event.orderId(), event.totalAmount(), event.paymentMethod());

            // 결제 성공 시 → PaymentCompletedEvent 발행 → 배달 서비스가 수신
            if (payment.getStatus().name().equals("COMPLETED")) {
                eventPublisher.publishPaymentCompleted(
                        payment.getId(), event.orderId(), event.totalAmount(), event.paymentMethod());
            } else {
                // 결제 실패 시 → PaymentFailedEvent 발행 → 주문 서비스가 보상 트랜잭션 실행
                eventPublisher.publishPaymentFailed(event.orderId(), "Payment processing failed");
            }
        } catch (Exception e) {
            log.error("Payment processing error: orderId={}", event.orderId(), e);
            // 예외 발생 시에도 실패 이벤트 발행하여 Saga 보상 처리 유도
            eventPublisher.publishPaymentFailed(event.orderId(), e.getMessage());
        }
    }
}
