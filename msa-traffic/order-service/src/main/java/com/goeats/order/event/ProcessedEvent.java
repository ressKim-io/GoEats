package com.goeats.order.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 처리된 이벤트 기록 엔티티 - Idempotent Consumer 패턴 구현
 *
 * <h3>역할</h3>
 * Kafka에서 수신한 이벤트의 eventId를 DB에 기록하여,
 * 동일 이벤트가 중복 수신되었을 때 재처리를 방지한다.
 *
 * <h3>왜 필요한가? (Kafka의 at-least-once 전달 보장)</h3>
 * <pre>
 * Kafka는 메시지를 "최소 1회" 전달한다 (at-least-once delivery).
 * → 네트워크 오류, 컨슈머 재시작, 리밸런싱 등으로 동일 메시지가 2번 이상 전달될 수 있다.
 * → 주문 상태를 2번 변경하거나, 결제를 2번 처리하는 문제를 방지해야 한다.
 * </pre>
 *
 * <h3>Idempotent Consumer 동작 흐름</h3>
 * <pre>
 * 1. 이벤트 수신 (eventId = "abc-123")
 * 2. processedEventRepository.existsById("abc-123") → false (미처리)
 * 3. 이벤트 처리 (주문 상태 변경 등)
 * 4. processedEventRepository.save(new ProcessedEvent("abc-123"))  [같은 @Transactional]
 *
 * 동일 이벤트 재수신 시:
 * 1. 이벤트 수신 (eventId = "abc-123")
 * 2. processedEventRepository.existsById("abc-123") → true (이미 처리됨)
 * 3. return; (건너뜀 - 멱등성 보장)
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 ProcessedEvent가 없어, Kafka 중복 수신 시
 * 동일 이벤트가 여러 번 처리되어 데이터 불일치가 발생할 수 있었다.
 * MSA-Traffic에서는 eventId 기반 멱등성 체크로 중복 처리를 완전히 방지한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 ApplicationEventPublisher로 동기 이벤트를 발행하므로
 * 중복 수신 문제가 없었다 (in-process 통신).
 * MSA에서는 네트워크를 통한 비동기 메시징이므로 멱등성 보장이 필수다.
 *
 * ★ Idempotent Consumer Pattern
 *
 * Problem: Kafka delivers at-least-once → duplicate events under traffic
 * Solution: Track processed eventIds in DB, skip duplicates
 *
 * Flow:
 *   1. Receive event with eventId
 *   2. Check if eventId exists in processed_events table
 *   3. If exists → skip (idempotent)
 *   4. If not → process event + insert eventId (same transaction)
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    // 이벤트 고유 식별자 (UUID): 이벤트 발행 시 생성되어 메시지에 포함됨
    @Id
    @Column(nullable = false)
    private String eventId;

    // 이벤트 처리 완료 시각: 언제 이 이벤트를 처리했는지 기록
    @Column(nullable = false)
    private LocalDateTime processedAt;

    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEvent that)) return false;
        return eventId != null && eventId.equals(that.getEventId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
