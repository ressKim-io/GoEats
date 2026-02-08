package com.goeats.order.service;

import com.goeats.common.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 이벤트 발행기: 직접 메서드 호출 대신 이벤트를 발행합니다.
 *
 * <p>MSA에서 서비스 간 통신의 비동기 방식입니다.
 * {@code KafkaTemplate.send(topic, key, value)}를 사용하여 이벤트를 발행하며,
 * key로 orderId를 사용하여 같은 주문의 이벤트가 항상 같은 Kafka 파티션으로
 * 전송되도록 보장합니다. 이를 통해 주문별 이벤트 순서가 보장됩니다.</p>
 *
 * <p>발행하는 이벤트:</p>
 * <ul>
 *   <li>{@code OrderCreatedEvent} - 주문 생성 시 → PaymentService가 수신하여 결제 처리</li>
 *   <li>{@code OrderCancelledEvent} - 주문 취소 시 → PaymentService/DeliveryService가 보상 처리</li>
 * </ul>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 {@code paymentService.processPayment(order, paymentMethod)}로
 * 같은 JVM 내에서 동기적으로 직접 호출합니다. 하나의 @Transactional로 묶여 있어
 * 실패 시 전체가 롤백됩니다. MSA에서는 Kafka를 통한 비동기 통신이므로,
 * 실패 시 보상 트랜잭션(Saga)이 필요합니다.</p>
 *
 * ★ MSA: Kafka event publisher replaces direct method calls.
 *
 * Instead of calling paymentService.processPayment() directly,
 * we publish an OrderCreatedEvent to Kafka. PaymentService subscribes
 * to this topic and processes payment asynchronously.
 *
 * Compare with Monolithic: paymentService.processPayment(order, paymentMethod)
 * - synchronous, same transaction, same JVM.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // 주문 생성 이벤트를 "order-events" 토픽으로 발행
    // key: orderId (같은 주문의 이벤트는 같은 파티션으로 → 순서 보장)
    // value: OrderCreatedEvent (JSON으로 직렬화되어 Kafka에 저장)
    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent: orderId={}", event.orderId());
        kafkaTemplate.send("order-events", String.valueOf(event.orderId()), event);
    }

    // 주문 취소 이벤트 발행 → PaymentService, DeliveryService가 수신하여 보상 처리
    public void publishOrderCancelled(Long orderId, String reason) {
        log.info("Publishing OrderCancelledEvent: orderId={}, reason={}", orderId, reason);
        kafkaTemplate.send("order-events", String.valueOf(orderId),
                new OrderCancelledEvent(orderId, reason));
    }

    // 주문 취소 이벤트 DTO
    public record OrderCancelledEvent(Long orderId, String reason) {}
}
