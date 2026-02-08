package com.goeats.order.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★ MSA: Kafka event listener for saga coordination.
 *
 * Listens for payment results from PaymentService:
 * - PaymentCompletedEvent → update order to PAID
 * - PaymentFailedEvent → update order to CANCELLED (saga compensation)
 *
 * Compare with Monolithic: No event listeners needed.
 * Payment result is returned synchronously from paymentService.processPayment().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderRepository orderRepository;

    @KafkaListener(topics = "payment-events", groupId = "order-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent: orderId={}", event.orderId());
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.PAID);
            log.info("Order {} updated to PAID", order.getId());
        });
    }

    @KafkaListener(topics = "payment-failed-events", groupId = "order-service")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent: orderId={}, reason={}",
                event.orderId(), event.reason());
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            // ★ MSA Saga compensation: cancel order when payment fails
            order.updateStatus(OrderStatus.CANCELLED);
            log.warn("Order {} CANCELLED due to payment failure: {}",
                    order.getId(), event.reason());
        });
    }
}
