package com.goeats.delivery.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 처리 완료 이벤트(ProcessedEvent) 엔티티 - Idempotent Consumer 패턴의 핵심 테이블.
 *
 * <p>Kafka 이벤트의 eventId를 기록하여 동일 이벤트의 중복 처리를 방지한다.
 * PaymentEventListener에서 이벤트 처리 전에 이 테이블을 조회하여
 * 이미 처리된 이벤트인지 확인한다.</p>
 *
 * <h3>동작 흐름</h3>
 * <pre>
 * 1. 이벤트 수신 → processedEventRepository.existsById(eventId) 확인
 * 2-A. 이미 존재 → 중복 이벤트이므로 스킵 (return)
 * 2-B. 존재하지 않음 → 비즈니스 로직 수행 → processedEventRepository.save(new ProcessedEvent(eventId))
 * </pre>
 *
 * <h3>왜 필요한가?</h3>
 * <p>Kafka는 "at-least-once" 전달을 보장한다. 즉, 네트워크 오류나 리밸런싱 등으로
 * 동일한 이벤트가 2번 이상 전달될 수 있다. 이 테이블 없이는 배달이 중복 생성될 수 있다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 단일 DB 트랜잭션으로 처리하므로 중복 처리 문제가 발생하지 않는다.
 * MSA에서는 서비스 간 비동기 메시징으로 인해 멱등성 보장이 필수적이다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 멱등성 체크 없이 이벤트를 처리하여 중복 생성 위험이 있다.
 * Traffic 버전에서는 ProcessedEvent 테이블로 "exactly-once" 처리를 보장한다.</p>
 *
 * ★ Idempotent Consumer for Delivery Service
 * Tracks processed Kafka event IDs to prevent duplicate delivery creation.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 프록시 생성용
public class ProcessedEvent {

    @Id
    @Column(nullable = false)
    private String eventId;  // Kafka 이벤트의 고유 식별자 (UUID)

    @Column(nullable = false)
    private LocalDateTime processedAt;  // 처리 완료 시각 (디버깅/모니터링용)

    /**
     * 이벤트 처리 완료 시 생성.
     *
     * @param eventId Kafka 이벤트의 고유 ID (PaymentCompletedEvent.eventId)
     */
    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();  // 처리 시각 자동 기록
    }
}
