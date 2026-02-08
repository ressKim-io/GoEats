package com.goeats.payment.event;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.common.outbox.OutboxService;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.entity.PaymentStatus;
import com.goeats.payment.service.PaymentService;
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
 * ★★★ Traffic MSA: Enhanced Order Event Listener
 *
 * vs Basic MSA:
 *   @KafkaListener + try/catch → message lost on failure
 *
 * Traffic MSA:
 *   @RetryableTopic(attempts=4) → auto retry with exponential backoff
 *   @DltHandler → dead letter topic for manual review
 *   ProcessedEvent → skip duplicate events
 *   outboxService.saveEvent() → atomic payment result publishing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final PaymentService paymentService;
    private final OutboxService outboxService;
    private final ProcessedEventRepository processedEventRepository;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = org.springframework.kafka.retrytopic.DltStrategy.ALWAYS_RETRY_ON_ERROR
    )
    @KafkaListener(topics = "order-events", groupId = "payment-service")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        // ★ Idempotent check
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing OrderCreatedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        try {
            Payment payment = paymentService.processPayment(
                    event.orderId(), event.totalAmount(),
                    event.paymentMethod(), event.eventId());

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                // ★ Outbox: atomic payment result publishing
                PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                        payment.getId(), event.orderId(),
                        event.totalAmount(), event.paymentMethod());

                outboxService.saveEvent("Payment", payment.getId().toString(),
                        "PaymentCompleted", completedEvent);

                log.info("Payment completed, outbox event saved: orderId={}", event.orderId());
            } else {
                PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                        event.orderId(), "Payment processing failed");

                outboxService.saveEvent("Payment", event.orderId().toString(),
                        "PaymentFailed", failedEvent);

                log.warn("Payment failed, outbox event saved: orderId={}", event.orderId());
            }
        } catch (Exception e) {
            log.error("Payment processing error: orderId={}", event.orderId(), e);
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                    event.orderId(), e.getMessage());
            outboxService.saveEvent("Payment", event.orderId().toString(),
                    "PaymentFailed", failedEvent);
        }

        // ★ Mark as processed
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    @DltHandler
    public void handleDlt(OrderCreatedEvent event) {
        log.error("OrderCreatedEvent sent to DLT. Manual intervention required: orderId={}",
                event.orderId());
    }
}
