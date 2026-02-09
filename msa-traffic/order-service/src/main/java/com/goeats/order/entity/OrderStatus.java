package com.goeats.order.entity;

/**
 * 주문 상태 열거형 - Saga 진행에 따른 주문 상태 전이
 *
 * <h3>상태 전이 다이어그램</h3>
 * <pre>
 * CREATED → PAYMENT_PENDING → PAID → PREPARING → DELIVERING → DELIVERED (정상 흐름)
 *     └───────────────────────────────────────────────────────→ CANCELLED (취소/보상)
 *                           └─────────────────────────────────→ CANCELLED (결제 실패)
 * </pre>
 *
 * <h3>각 상태 설명</h3>
 * <ul>
 *   <li>CREATED: 주문 생성 완료 (아직 결제 미요청)</li>
 *   <li>PAYMENT_PENDING: 결제 요청 중 (Outbox → Kafka → Payment 서비스)</li>
 *   <li>PAID: 결제 완료 (PaymentCompletedEvent 수신)</li>
 *   <li>PREPARING: 가게에서 조리 중</li>
 *   <li>DELIVERING: 라이더가 배달 중</li>
 *   <li>DELIVERED: 배달 완료</li>
 *   <li>CANCELLED: 주문 취소 (사용자 취소 또는 Saga 보상 트랜잭션)</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서도 동일한 상태를 사용했지만, 상태 전이가 Kafka 이벤트로만 관리되었다.
 * MSA-Traffic에서는 SagaState 엔티티가 함께 상태 전이를 추적하여
 * 어느 단계에서 실패했는지 디버깅이 가능하다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 하나의 @Transactional에서 주문+결제+배달을 처리하므로
 * 중간 상태(PAYMENT_PENDING 등)가 불필요했다.
 * MSA에서는 분산 트랜잭션이 비동기로 진행되므로 세분화된 상태 관리가 필수다.
 */
public enum OrderStatus {
    CREATED,           // 주문 생성 완료
    PAYMENT_PENDING,   // 결제 대기 중 (Outbox 이벤트 발행됨)
    PAID,              // 결제 완료
    PREPARING,         // 가게에서 조리 중
    DELIVERING,        // 라이더 배달 중
    DELIVERED,         // 배달 완료
    CANCELLED          // 주문 취소 (사용자 취소 또는 Saga 보상)
}
