package com.goeats.order.service;

import com.goeats.common.event.OrderCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
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

    public void publishOrderCreated(OrderCreatedEvent event) {
        log.info("Publishing OrderCreatedEvent: orderId={}", event.orderId());
        kafkaTemplate.send("order-events", String.valueOf(event.orderId()), event);
    }

    public void publishOrderCancelled(Long orderId, String reason) {
        log.info("Publishing OrderCancelledEvent: orderId={}, reason={}", orderId, reason);
        kafkaTemplate.send("order-events", String.valueOf(orderId),
                new OrderCancelledEvent(orderId, reason));
    }

    public record OrderCancelledEvent(Long orderId, String reason) {}
}
