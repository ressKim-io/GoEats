package com.goeats.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * ★ Transactional Outbox - Event Save Helper
 *
 * Called WITHIN the caller's @Transactional boundary.
 * The event is saved to the outbox table in the SAME transaction
 * as the business entity, guaranteeing atomicity.
 *
 * Usage:
 *   @Transactional
 *   public Order createOrder(...) {
 *       Order order = orderRepository.save(order);
 *       outboxService.saveEvent("Order", order.getId().toString(),
 *                               "OrderCreated", event);
 *       return order;  // both saved or both rolled back
 *   }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void saveEvent(String aggregateType, String aggregateId,
                          String eventType, Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payload)
                    .build();
            outboxEventRepository.save(outboxEvent);
            log.debug("Outbox event saved: type={}, aggregateId={}", eventType, aggregateId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize outbox event: {}", eventType, e);
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
    }
}
