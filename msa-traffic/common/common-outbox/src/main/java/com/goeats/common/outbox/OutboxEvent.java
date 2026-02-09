package com.goeats.common.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트 엔티티 (Transactional Outbox Pattern)
 *
 * <p>Kafka로 발행할 이벤트를 DB 테이블(outbox_events)에 저장하는 JPA 엔티티.
 * 비즈니스 엔티티와 같은 트랜잭션에서 저장되어 <b>원자성(Atomicity)</b>을 보장.</p>
 *
 * <h3>Transactional Outbox 패턴이란?</h3>
 * <pre>
 *   [문제] DB 커밋 후 kafkaTemplate.send() 호출 시:
 *         → DB 커밋 성공 + Kafka 발행 실패 = 이벤트 유실!
 *         → DB 롤백 + Kafka 발행 성공 = 유령 이벤트!
 *
 *   [해결] 같은 DB 트랜잭션에서 비즈니스 데이터 + Outbox 이벤트를 함께 저장.
 *         별도 Relay(스케줄러)가 미발행 이벤트를 Kafka로 전송.
 *
 *   [보장] At-least-once delivery (최소 1번 전달)
 *         → 소비자 측에서 Idempotent Consumer 패턴으로 중복 처리 방지 필수!
 * </pre>
 *
 * <h3>테이블 구조</h3>
 * <pre>
 *   outbox_events (
 *     id             BIGINT       -- PK (시퀀스, allocationSize=50 성능 최적화)
 *     aggregate_type VARCHAR      -- 집합체 유형 (예: "Order", "Payment")
 *     aggregate_id   VARCHAR      -- 집합체 ID (예: 주문 ID)
 *     event_type     VARCHAR      -- 이벤트 유형 (예: "OrderCreated", "PaymentCompleted")
 *     payload        TEXT         -- 이벤트 JSON 직렬화 데이터
 *     published      BOOLEAN      -- Kafka 발행 여부
 *     created_at     TIMESTAMP    -- 생성 시각
 *     published_at   TIMESTAMP    -- 발행 시각 (발행 후 기록)
 *   )
 *   INDEX: idx_outbox_published (published, createdAt) -- Relay의 미발행 이벤트 조회 최적화
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 같은 DB 트랜잭션 안에서 모든 서비스 로직이 실행되므로
 * Outbox 테이블이 불필요. ApplicationEventPublisher로 동기 이벤트 전파.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 Outbox 없이 비즈니스 로직 후 직접 KafkaTemplate.send() 호출.
 * 고트래픽 환경에서 Kafka 브로커 장애 시 이벤트 유실 발생 가능.</p>
 *
 * ★ Transactional Outbox Pattern
 *
 * Problem: kafkaTemplate.send() after DB commit can fail silently,
 *          causing event loss under high traffic.
 *
 * Solution: Save event to outbox table within the SAME transaction as
 *           the business entity. A scheduled relay polls unpublished
 *           events and sends them to Kafka.
 *
 * Guarantee: At-least-once delivery (consumers must be idempotent).
 */
@Entity
@Table(name = "outbox_events", indexes = {
        // 미발행(published=false) 이벤트를 생성 시간순으로 빠르게 조회하기 위한 인덱스
        @Index(name = "idx_outbox_published", columnList = "published, createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 프록시 생성을 위한 protected 기본 생성자
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbox_event_seq")
    @SequenceGenerator(name = "outbox_event_seq", sequenceName = "outbox_event_seq", allocationSize = 50)
    private Long id; // PK - 시퀀스 기반, allocationSize=50으로 DB 왕복 최소화

    @Column(nullable = false)
    private String aggregateType; // 집합체 유형 (예: "Order", "Payment", "Delivery")

    @Column(nullable = false)
    private String aggregateId; // 집합체 ID (예: 주문 ID "123") - Kafka 메시지 키로도 사용

    @Column(nullable = false)
    private String eventType; // 이벤트 유형 (예: "OrderCreated") - 토픽 이름 결정에 사용

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload; // 이벤트 JSON 페이로드 (ObjectMapper로 직렬화된 이벤트 DTO)

    @Column(nullable = false)
    private boolean published; // Kafka 발행 완료 여부 (Relay가 발행 후 true로 변경)

    @Column(nullable = false)
    private LocalDateTime createdAt; // 이벤트 생성 시각 (FIFO 순서 보장에 사용)

    private LocalDateTime publishedAt; // Kafka 발행 시각 (발행 전에는 null)

    /**
     * Outbox 이벤트 생성 빌더.
     * published=false, createdAt=현재시간으로 초기화됨.
     * OutboxService.saveEvent()에서 호출됨.
     */
    @Builder
    public OutboxEvent(String aggregateType, String aggregateId,
                       String eventType, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        this.published = false;         // 생성 시 미발행 상태
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Kafka 발행 완료 표시.
     * OutboxRelay가 Kafka 전송 성공 후 호출.
     * 같은 트랜잭션 내에서 실행되어 발행 상태가 원자적으로 업데이트됨.
     */
    public void markPublished() {
        this.published = true;
        this.publishedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent that)) return false;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
