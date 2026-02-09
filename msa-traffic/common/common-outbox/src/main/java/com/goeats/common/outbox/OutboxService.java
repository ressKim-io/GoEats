package com.goeats.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Outbox 이벤트 저장 서비스 (Transactional Outbox - Event Save Helper)
 *
 * <p>비즈니스 트랜잭션 안에서 호출되어 이벤트를 Outbox 테이블에 저장하는 헬퍼 서비스.
 * <b>호출자의 @Transactional 범위 안에서 실행</b>되어 비즈니스 데이터와 이벤트의 원자성 보장.</p>
 *
 * <h3>핵심 원리: "같은 트랜잭션"</h3>
 * <pre>
 *   @Transactional  // ← 이 트랜잭션 안에서 두 작업이 함께 커밋 또는 롤백
 *   public Order createOrder(...) {
 *       Order order = orderRepository.save(order);           // 1. 비즈니스 엔티티 저장
 *       outboxService.saveEvent("Order", order.getId()...,   // 2. Outbox 이벤트 저장
 *                               "OrderCreated", event);
 *       return order;  // 커밋 시 둘 다 저장, 롤백 시 둘 다 취소!
 *   }
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 ApplicationEventPublisher.publishEvent()로 같은 JVM 내
 * 이벤트 발행. DB에 이벤트를 별도 저장할 필요 없음.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 비즈니스 로직 후 kafkaTemplate.send() 직접 호출:</p>
 * <pre>
 *   @Transactional
 *   public Order createOrder(...) {
 *       Order order = orderRepository.save(order);
 *       kafkaTemplate.send("order-events", event);  // DB 커밋 전에 Kafka 전송!
 *       return order;                                 // 이 시점에서 장애 발생 시?
 *   }
 *   // 문제: DB 롤백 → Kafka에는 이미 메시지 전송됨 (유령 이벤트!)
 *   // 문제: DB 커밋 → Kafka 전송 실패 시 이벤트 유실!
 * </pre>
 * <p>MSA-Traffic에서는 OutboxService로 DB 저장만 하고, 실제 Kafka 전송은
 * {@link OutboxRelay}가 별도로 처리하여 두 문제를 모두 해결.</p>
 *
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

    private final OutboxEventRepository outboxEventRepository; // Outbox 테이블 접근 리포지토리
    private final ObjectMapper objectMapper; // 이벤트 객체 → JSON 직렬화

    /**
     * Outbox 테이블에 이벤트를 저장.
     * 반드시 호출자의 @Transactional 범위 안에서 호출되어야 원자성이 보장됨.
     *
     * @param aggregateType 집합체 유형 (예: "Order", "Payment", "Delivery")
     * @param aggregateId   집합체 ID (예: 주문 ID "123") - Kafka 메시지 키로 사용되어 파티션 결정
     * @param eventType     이벤트 유형 (예: "OrderCreated") - Relay에서 토픽명 결정에 사용
     * @param event         이벤트 객체 (JSON으로 직렬화되어 payload 컬럼에 저장)
     */
    public void saveEvent(String aggregateType, String aggregateId,
                          String eventType, Object event) {
        try {
            // 이벤트 객체를 JSON 문자열로 직렬화
            String payload = objectMapper.writeValueAsString(event);
            // OutboxEvent 엔티티 생성 (published=false, createdAt=now)
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payload)
                    .build();
            // DB에 저장 (호출자의 트랜잭션에 참여)
            outboxEventRepository.save(outboxEvent);
            log.debug("Outbox event saved: type={}, aggregateId={}", eventType, aggregateId);
        } catch (JsonProcessingException e) {
            // JSON 직렬화 실패 시 RuntimeException으로 래핑 → 트랜잭션 롤백 유도
            log.error("Failed to serialize outbox event: {}", eventType, e);
            throw new RuntimeException("Failed to serialize outbox event", e);
        }
    }
}
