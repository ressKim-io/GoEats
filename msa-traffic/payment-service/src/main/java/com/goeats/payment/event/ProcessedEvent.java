package com.goeats.payment.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 처리 완료 이벤트 엔티티 - Idempotent Consumer 패턴 구현.
 *
 * <p>Kafka에서 수신한 이벤트의 eventId를 기록하여, 동일한 이벤트가 재전달되었을 때
 * 중복 처리를 방지하는 "멱등성 소비자(Idempotent Consumer)" 패턴을 구현한다.</p>
 *
 * <h3>동작 원리</h3>
 * <pre>
 *   1. 이벤트 수신 시: processedEventRepository.existsById(eventId) 확인
 *   2. 이미 존재하면: 이벤트 처리 스킵 (중복 방지)
 *   3. 존재하지 않으면: 이벤트 처리 후 ProcessedEvent 저장 (처리 기록)
 * </pre>
 *
 * <h3>왜 필요한가?</h3>
 * <p>Kafka는 "at-least-once" 전달을 보장하므로, 같은 메시지가 2번 이상 전달될 수 있다.
 * 특히 @RetryableTopic으로 재시도하는 경우, 이전 시도에서 DB 커밋은 성공했지만
 * Kafka offset 커밋에 실패하면 같은 이벤트가 다시 전달된다.
 * 이때 ProcessedEvent 테이블로 "이미 처리했다"는 사실을 확인하여 중복 결제를 방지한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 이 엔티티가 없었다. Kafka 메시지가 중복 전달되면 같은 주문에 대해
 * 결제가 2번 생성될 수 있는 위험이 있었다. Traffic에서는 ProcessedEvent 테이블로
 * 이벤트 레벨의 멱등성을 보장한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 같은 JVM 내 동기 호출이므로 메시지 중복 전달 문제가 없었다.
 * 분산 환경에서 Kafka를 통해 비동기 통신하는 MSA에서만 필요한 패턴이다.</p>
 *
 * @see OrderEventListener#handleOrderCreated(com.goeats.common.event.OrderCreatedEvent)
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    // 이벤트 고유 ID (PK) - OrderCreatedEvent의 eventId와 동일한 값
    @Id
    @Column(nullable = false)
    private String eventId;

    // 이벤트가 처리된 시각 - 디버깅 및 모니터링 용도
    @Column(nullable = false)
    private LocalDateTime processedAt;

    /**
     * 처리 완료 이벤트 생성.
     * <p>processedAt은 현재 시각으로 자동 설정된다.</p>
     *
     * @param eventId 처리된 이벤트의 고유 ID
     */
    public ProcessedEvent(String eventId) {
        this.eventId = eventId;
        this.processedAt = LocalDateTime.now();
    }
}
