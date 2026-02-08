package com.goeats.order.entity;

/**
 * 주문 상태 열거형 (Order Status Enum).
 *
 * <p>주문의 생명주기를 나타내는 상태값입니다. 정상 흐름은 다음과 같습니다:</p>
 * <pre>
 * CREATED → PAYMENT_PENDING → PAID → PREPARING → DELIVERING → DELIVERED
 * </pre>
 *
 * <p>CANCELLED는 어느 단계에서든 전이(transition) 가능합니다.
 * MSA에서 이것은 Saga 보상 트랜잭션(Compensation Transaction)의 결과입니다.
 * 예: 결제 실패 시 PaymentService가 PaymentFailedEvent를 발행하면,
 * OrderService가 이를 수신하여 주문을 CANCELLED로 변경합니다.</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서도 동일한 상태값을 사용하지만,
 * 상태 전이가 하나의 @Transactional 안에서 동기적으로 일어납니다.
 * MSA에서는 각 상태 전이가 서로 다른 서비스에서 비동기적으로 발생하므로,
 * 중간 상태(PAYMENT_PENDING 등)가 클라이언트에게 노출됩니다.</p>
 */
public enum OrderStatus {
    CREATED,            // 주문 생성됨 (초기 상태)
    PAYMENT_PENDING,    // 결제 대기 중 (Kafka 이벤트 발행 후)
    PAID,               // 결제 완료 (PaymentService에서 이벤트 수신)
    PREPARING,          // 가게에서 준비 중
    DELIVERING,         // 배달 중 (DeliveryService에서 라이더 매칭 완료)
    DELIVERED,          // 배달 완료
    CANCELLED           // 취소됨 (Saga 보상 트랜잭션 또는 사용자 취소)
}
