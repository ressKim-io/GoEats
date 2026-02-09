package com.goeats.payment.event;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 처리 완료 이벤트 레포지토리 - Idempotent Consumer 패턴의 저장소.
 *
 * <p>Kafka 이벤트의 eventId를 키로 사용하여 이미 처리된 이벤트를 조회하고 저장한다.
 * {@code existsById(eventId)} 메서드로 중복 이벤트 여부를 O(1)에 확인할 수 있다.</p>
 *
 * <h3>주요 사용처</h3>
 * <ul>
 *   <li>{@code OrderEventListener.handleOrderCreated()} - 이벤트 수신 시 중복 체크</li>
 *   <li>처리 완료 후 {@code save(new ProcessedEvent(eventId))}로 기록</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에는 존재하지 않는 레포지토리. Traffic에서 멱등성 보장을 위해 추가되었다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 동기 메서드 호출이므로 이벤트 중복 문제 자체가 발생하지 않아 불필요하다.</p>
 *
 * @see ProcessedEvent
 * @see OrderEventListener
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
