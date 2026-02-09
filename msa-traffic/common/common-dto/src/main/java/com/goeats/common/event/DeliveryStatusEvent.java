package com.goeats.common.event;

import java.util.UUID;

/**
 * 배달 상태 변경 이벤트 (Delivery Status Change Event)
 *
 * <p>배달 서비스에서 주문 서비스로 배달 상태 변경을 알리는 이벤트 DTO.
 * 배달 시작, 픽업 완료, 배달 완료 등의 상태 전이 시 발행됨.</p>
 *
 * <h3>이벤트 흐름</h3>
 * <pre>
 *   Delivery Service → [Kafka: delivery-events] → Order Service
 *   (라이더 매칭 → 픽업 → 배달 완료)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 DeliveryService가 직접 OrderService 메서드를 호출하여
 * 상태를 업데이트. 이벤트 DTO 자체가 불필요함.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 eventId 없이 Kafka로 직접 발행 → 중복 소비 시 배달 상태가
 * 꼬일 수 있음. MSA-Traffic에서는:</p>
 * <ul>
 *   <li><b>eventId</b>: UUID 기반 고유 식별자로 Idempotent Consumer 패턴 지원</li>
 *   <li><b>Outbox 패턴</b>: 직접 Kafka 발행 대신, OutboxService로 DB에 먼저 저장 후 릴레이가 발행</li>
 *   <li><b>Fencing Token</b>: 배달 서비스에서 동시 라이더 매칭 방지를 위한 추가 보호</li>
 * </ul>
 *
 * ★ Traffic MSA: eventId added for idempotent delivery status updates.
 * Prevents duplicate status change notifications.
 */
public record DeliveryStatusEvent(
        String eventId,    // 멱등성 보장을 위한 이벤트 고유 ID (UUID)
        Long deliveryId,   // 배달 엔티티 ID
        Long orderId,      // 연관 주문 ID (Order Service가 상태 업데이트에 사용)
        String status,     // 배달 상태 (ASSIGNED, PICKED_UP, DELIVERED 등)
        String riderName,  // 배달 기사 이름
        String riderPhone  // 배달 기사 연락처
) {
    /**
     * eventId 자동 생성 편의 생성자.
     * 비즈니스 로직에서 직접 eventId를 지정하지 않아도 UUID가 자동 할당됨.
     */
    public DeliveryStatusEvent(Long deliveryId, Long orderId,
                               String status, String riderName, String riderPhone) {
        this(UUID.randomUUID().toString(), deliveryId, orderId,
                status, riderName, riderPhone);
    }
}
