package com.goeats.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Outbox 이벤트 리포지토리 (Outbox Event Repository)
 *
 * <p>Outbox 테이블에서 미발행 이벤트를 조회하는 Spring Data JPA 리포지토리.
 * {@link OutboxRelay}가 주기적으로 호출하여 Kafka로 발행할 이벤트를 가져옴.</p>
 *
 * <h3>핵심 쿼리</h3>
 * <pre>
 *   SELECT * FROM outbox_events
 *   WHERE published = false
 *   ORDER BY created_at ASC;  -- FIFO 순서 보장 (이벤트 순서가 중요!)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 이벤트를 DB에 저장하지 않으므로 이 리포지토리가 불필요.
 * ApplicationEventPublisher로 인메모리 이벤트 전파.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 Outbox 테이블이 없으므로 이 리포지토리도 없음.
 * MSA-Traffic에서 Outbox 패턴 도입으로 추가된 컴포넌트.</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Find unpublished events ordered by creation time (FIFO).
     * The relay polls this to send events to Kafka.
     *
     * 미발행 이벤트를 생성 시간 오름차순으로 조회.
     * FIFO 순서 보장이 중요: 주문 생성 → 결제 완료 → 배달 시작 순서가 뒤바뀌면 안 됨.
     * idx_outbox_published 인덱스를 활용하여 효율적으로 조회.
     */
    List<OutboxEvent> findByPublishedFalseOrderByCreatedAtAsc();
}
