package com.goeats.delivery.event;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ProcessedEvent 레포지토리 - Idempotent Consumer 패턴의 데이터 접근 계층.
 *
 * <p>주요 사용 메서드:</p>
 * <ul>
 *   <li>{@code existsById(eventId)}: 이벤트가 이미 처리되었는지 확인 (중복 체크)</li>
 *   <li>{@code save(ProcessedEvent)}: 이벤트 처리 완료 기록</li>
 * </ul>
 *
 * <p>제네릭 타입: {@code JpaRepository<ProcessedEvent, String>}
 * - PK 타입이 String (eventId가 UUID 문자열이므로)</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 이벤트 중복 처리 문제가 없으므로 이 레포지토리가 존재하지 않는다.</p>
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
