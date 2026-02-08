package com.goeats.delivery.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★★★ Traffic MSA: Enhanced Payment Event Listener for Delivery
 *
 * vs Basic MSA:
 *   @KafkaListener + try/catch → delivery creation lost on failure
 *
 * Traffic MSA:
 *   @RetryableTopic → auto retry failed delivery creation
 *   ProcessedEvent → skip duplicate PaymentCompletedEvents
 *   @DltHandler → log permanently failed events for manual review
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final DeliveryService deliveryService;
    private final ProcessedEventRepository processedEventRepository;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.ALWAYS_RETRY_ON_ERROR
    )
    @KafkaListener(topics = "payment-events", groupId = "delivery-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        // ★ Idempotent check
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentCompletedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        try {
            deliveryService.createDelivery(event.orderId(), "Default Address");
            log.info("Delivery created for order: {}", event.orderId());
        } catch (Exception e) {
            log.error("Failed to create delivery for order: {}", event.orderId(), e);
            throw e; // Rethrow for @RetryableTopic to handle retry
        }

        // ★ Mark as processed
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    @DltHandler
    public void handleDlt(PaymentCompletedEvent event) {
        log.error("PaymentCompletedEvent sent to DLT. Manual intervention required: orderId={}",
                event.orderId());
    }
}
