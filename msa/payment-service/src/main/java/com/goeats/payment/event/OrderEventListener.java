package com.goeats.payment.event;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

    @KafkaListener(topics = "order-events", groupId = "payment-service")
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Received OrderCreatedEvent: orderId={}", event.orderId());

        try {
            Payment payment = paymentService.processPayment(
                    event.orderId(), event.totalAmount(), event.paymentMethod());

            if (payment.getStatus().name().equals("COMPLETED")) {
                eventPublisher.publishPaymentCompleted(
                        payment.getId(), event.orderId(), event.totalAmount(), event.paymentMethod());
            } else {
                eventPublisher.publishPaymentFailed(event.orderId(), "Payment processing failed");
            }
        } catch (Exception e) {
            log.error("Payment processing error: orderId={}", event.orderId(), e);
            eventPublisher.publishPaymentFailed(event.orderId(), e.getMessage());
        }
    }
}
