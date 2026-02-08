package com.goeats.delivery.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * ★ MSA: Kafka listener triggers delivery creation when payment completes.
 *
 * Saga flow: OrderCreated → PaymentCompleted → DeliveryCreated
 *
 * Compare with Monolithic: deliveryService.createDelivery(order)
 * called directly in OrderService within same @Transactional.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final DeliveryService deliveryService;

    @KafkaListener(topics = "payment-events", groupId = "delivery-service")
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent: orderId={}", event.orderId());

        try {
            deliveryService.createDelivery(event.orderId(), "Default Address");
            log.info("Delivery created for order: {}", event.orderId());
        } catch (Exception e) {
            log.error("Failed to create delivery for order: {}", event.orderId(), e);
            // In production: publish DeliveryFailedEvent for saga compensation
        }
    }
}
