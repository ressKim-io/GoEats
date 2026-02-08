package com.goeats.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 주문 생성 이벤트 (Order Created Event)
 *
 * <p>주문 서비스에서 주문이 생성되었을 때 발행하는 핵심 이벤트 DTO.
 * Saga 패턴의 시작점으로, 결제 서비스가 이 이벤트를 수신하여 결제를 진행함.</p>
 *
 * <h3>Saga 흐름 (Choreography)</h3>
 * <pre>
 *   Order Service → [Kafka: order-events] → Payment Service
 *                                            ├─ 성공 → PaymentCompletedEvent → Delivery Service
 *                                            └─ 실패 → PaymentFailedEvent → Order Service (보상 트랜잭션)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 OrderService.createOrder() 안에서 PaymentService.pay()를
 * 직접 호출하고 @Transactional로 원자성 보장.
 * MSA에서는 서비스가 분리되어 있으므로 이벤트를 통한 비동기 통신이 필수.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 eventId 없이 KafkaTemplate으로 직접 발행.
 * MSA-Traffic에서는:</p>
 * <ul>
 *   <li><b>eventId</b>: Idempotent Consumer 패턴 - 소비자가 ProcessedEvent 테이블로 중복 처리 방지</li>
 *   <li><b>Outbox 패턴 연동</b>: 이 이벤트는 KafkaTemplate.send()로 직접 보내지 않고,
 *       OutboxService.saveEvent()로 DB에 저장 → OutboxRelay가 Kafka로 발행</li>
 *   <li><b>@RetryableTopic</b>: 소비 실패 시 자동 재시도 + DLQ(Dead Letter Queue) 전송</li>
 * </ul>
 *
 * ★ Traffic MSA: eventId added for idempotent event processing.
 * Each consumer tracks processed eventIds to prevent duplicate handling.
 */
public record OrderCreatedEvent(
        String eventId,          // 멱등성 보장을 위한 이벤트 고유 ID (UUID)
        Long orderId,            // 주문 ID (Saga의 correlation key)
        Long userId,             // 주문자 사용자 ID
        Long storeId,            // 주문 대상 가게 ID
        List<OrderItemDto> items, // 주문 항목 리스트 (메뉴ID, 수량, 가격)
        BigDecimal totalAmount,  // 총 주문 금액 (결제 서비스에서 사용)
        String deliveryAddress,  // 배달 주소
        String paymentMethod     // 결제 수단 (CARD, CASH 등)
) {
    /**
     * eventId 자동 생성 편의 생성자.
     * Order Service의 비즈니스 로직에서 주문 생성 시 사용.
     */
    public OrderCreatedEvent(Long orderId, Long userId, Long storeId,
                             List<OrderItemDto> items, BigDecimal totalAmount,
                             String deliveryAddress, String paymentMethod) {
        this(UUID.randomUUID().toString(), orderId, userId, storeId,
                items, totalAmount, deliveryAddress, paymentMethod);
    }

    /**
     * 주문 항목 DTO (중첩 record).
     * 메뉴 ID, 수량, 개별 가격 정보를 담는 경량 DTO.
     */
    public record OrderItemDto(Long menuId, int quantity, BigDecimal price) {}
}
