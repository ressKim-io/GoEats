package com.goeats.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 결제 완료 이벤트 (Payment Completed Event)
 *
 * <p>결제 서비스에서 결제가 성공했을 때 발행하는 이벤트 DTO.
 * Saga 패턴에서 "결제 성공" 단계를 나타내며, 주문 서비스와 배달 서비스가 수신.</p>
 *
 * <h3>이벤트 흐름</h3>
 * <pre>
 *   Payment Service → [Kafka: payment-events] → Order Service (주문 상태 → PAID)
 *                                              → Delivery Service (배달 생성 + 라이더 매칭)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 PaymentService.pay() 성공 후 OrderService.updateStatus()와
 * DeliveryService.createDelivery()를 같은 트랜잭션에서 순차 호출.
 * 하나라도 실패하면 전체 롤백되므로 이벤트가 필요 없음.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 KafkaTemplate으로 직접 발행하며, 결제 DB 커밋과 Kafka 발행 사이에
 * 장애 발생 시 이벤트 유실 가능. MSA-Traffic에서는:</p>
 * <ul>
 *   <li><b>Outbox 패턴</b>: 결제 엔티티와 같은 트랜잭션으로 OutboxEvent 저장 → 이벤트 유실 불가</li>
 *   <li><b>eventId</b>: 주문/배달 서비스에서 중복 수신 시 ProcessedEvent로 멱등성 보장</li>
 *   <li><b>At-least-once 보장</b>: OutboxRelay가 미발행 이벤트를 재발행 → 소비자 측 멱등 처리 필수</li>
 * </ul>
 *
 * ★ Traffic MSA: eventId added for idempotent event processing.
 * Prevents duplicate payment confirmation handling in Order/Delivery services.
 */
public record PaymentCompletedEvent(
        String eventId,        // 멱등성 보장을 위한 이벤트 고유 ID (UUID)
        Long paymentId,        // 결제 엔티티 ID
        Long orderId,          // 연관 주문 ID (Saga correlation key)
        BigDecimal amount,     // 결제 금액
        String paymentMethod   // 결제 수단 (CARD, CASH 등)
) {
    /**
     * eventId 자동 생성 편의 생성자.
     * Payment Service에서 결제 성공 후 이벤트 생성 시 사용.
     */
    public PaymentCompletedEvent(Long paymentId, Long orderId,
                                 BigDecimal amount, String paymentMethod) {
        this(UUID.randomUUID().toString(), paymentId, orderId, amount, paymentMethod);
    }
}
