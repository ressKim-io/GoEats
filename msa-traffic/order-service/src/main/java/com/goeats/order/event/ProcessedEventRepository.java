package com.goeats.order.event;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ProcessedEvent 저장소 - Idempotent Consumer 패턴의 데이터 액세스 계층
 *
 * <h3>역할</h3>
 * 처리된 이벤트의 eventId를 저장/조회하여 중복 이벤트 처리를 방지한다.
 *
 * <h3>주요 사용 메서드</h3>
 * <ul>
 *   <li>existsById(eventId): 이벤트가 이미 처리되었는지 확인 (멱등성 체크)</li>
 *   <li>save(processedEvent): 이벤트 처리 완료 기록</li>
 * </ul>
 *
 * <h3>제네릭 파라미터</h3>
 * - ProcessedEvent: 엔티티 타입
 * - String: ID(eventId) 타입 (UUID 문자열)
 *
 * @see ProcessedEvent 처리된 이벤트 엔티티
 * @see PaymentEventListener 이 저장소를 사용하는 이벤트 리스너
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
