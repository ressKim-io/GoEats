package com.goeats.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ★ Transactional Outbox - Relay (Polling Publisher)
 *
 * Polls the outbox table every second for unpublished events
 * and sends them to the corresponding Kafka topic.
 *
 * Flow:
 *   1. Query unpublished events (FIFO order)
 *   2. Send each event to Kafka topic (derived from eventType)
 *   3. Mark as published within a transaction
 *
 * Topic naming convention:
 *   - "OrderCreated"  → "order-events"
 *   - "PaymentCompleted" → "payment-events"
 *   - "PaymentFailed" → "payment-failed-events"
 *   - "DeliveryStatus" → "delivery-events"
 *
 * Note: In multi-instance deployments, use ShedLock or similar
 *       to prevent duplicate relay execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pendingEvents =
                outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

        for (OutboxEvent event : pendingEvents) {
            try {
                String topic = resolveTopicName(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
                event.markPublished();
                log.debug("Outbox relay published: topic={}, aggregateId={}",
                        topic, event.getAggregateId());
            } catch (Exception e) {
                log.error("Outbox relay failed: eventId={}, type={}",
                        event.getId(), event.getEventType(), e);
                break; // Stop processing to maintain order
            }
        }
    }

    private String resolveTopicName(String eventType) {
        return switch (eventType) {
            case "OrderCreated" -> "order-events";
            case "PaymentCompleted" -> "payment-events";
            case "PaymentFailed" -> "payment-failed-events";
            case "DeliveryStatus" -> "delivery-events";
            default -> "unknown-events";
        };
    }
}
