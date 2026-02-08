package com.goeats.order.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import com.goeats.order.repository.SagaStateRepository;
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
 * ★★★ Traffic MSA: Enhanced Kafka Listener with DLQ
 *
 * vs Basic MSA:
 *   @KafkaListener → message lost on processing failure
 *
 * Traffic MSA:
 *   @RetryableTopic → automatic retry with exponential backoff
 *   @DltHandler → dead letter topic for permanently failed messages
 *   ProcessedEvent → idempotent consumption (skip duplicates)
 *
 * Retry flow:
 *   payment-events → payment-events-retry-0 → payment-events-retry-1
 *   → payment-events-retry-2 → payment-events-dlt (dead letter)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaStateRepository;
    private final ProcessedEventRepository processedEventRepository;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.ALWAYS_RETRY_ON_ERROR
    )
    @KafkaListener(topics = "payment-events", groupId = "order-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        // ★ Idempotent check: skip if already processed
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentCompletedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.PAID);
            log.info("Order {} updated to PAID", order.getId());
        });

        // ★ Update saga state
        sagaStateRepository.findByOrderId(event.orderId())
                .ifPresent(saga -> {
                    saga.advanceStep("PAYMENT_COMPLETED");
                    log.info("Saga advanced to PAYMENT_COMPLETED for orderId={}", event.orderId());
                });

        // ★ Mark event as processed (idempotent consumer)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE
    )
    @KafkaListener(topics = "payment-failed-events", groupId = "order-service")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentFailedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        // ★ Saga compensation: cancel order on payment failure
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.CANCELLED);
            log.warn("Order {} CANCELLED due to payment failure: {}",
                    order.getId(), event.reason());
        });

        sagaStateRepository.findByOrderId(event.orderId())
                .ifPresent(saga -> saga.startCompensation(event.reason()));

        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    // ★ Dead Letter Topic handler: log permanently failed messages for manual review
    @DltHandler
    public void handleDlt(Object event) {
        log.error("Message sent to DLT (dead letter topic). Manual intervention required: {}", event);
    }
}
