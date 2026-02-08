package com.goeats.payment.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishPaymentCompleted(Long paymentId, Long orderId,
                                         BigDecimal amount, String paymentMethod) {
        PaymentCompletedEvent event = new PaymentCompletedEvent(
                paymentId, orderId, amount, paymentMethod);
        log.info("Publishing PaymentCompletedEvent: orderId={}", orderId);
        kafkaTemplate.send("payment-events", String.valueOf(orderId), event);
    }

    public void publishPaymentFailed(Long orderId, String reason) {
        PaymentFailedEvent event = new PaymentFailedEvent(orderId, reason);
        log.info("Publishing PaymentFailedEvent: orderId={}, reason={}", orderId, reason);
        kafkaTemplate.send("payment-failed-events", String.valueOf(orderId), event);
    }
}
