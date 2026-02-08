package com.goeats.delivery.entity;

/**
 * 배달 상태(DeliveryStatus) 열거형 - 배달의 전체 생명주기를 정의한다.
 *
 * <h3>상태 전이 흐름</h3>
 * <pre>
 * WAITING → RIDER_ASSIGNED → PICKED_UP → DELIVERING → DELIVERED
 *    ↓
 * CANCELLED (어느 단계에서든 취소 가능)
 * </pre>
 *
 * <ul>
 *   <li>{@code WAITING} - 배달 대기중 (결제 완료 후 라이더 매칭 전)</li>
 *   <li>{@code RIDER_ASSIGNED} - 라이더 배정 완료</li>
 *   <li>{@code PICKED_UP} - 라이더가 가게에서 음식을 픽업 완료</li>
 *   <li>{@code DELIVERING} - 배달 진행중 (고객에게 이동중)</li>
 *   <li>{@code DELIVERED} - 배달 완료</li>
 *   <li>{@code CANCELLED} - 배달 취소</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서도 동일한 열거형을 사용하지만, 상태 변경이 하나의 트랜잭션 안에서 동기적으로 일어난다.
 * MSA에서는 상태 변경 시 Kafka 이벤트를 발행하여 다른 서비스(주문 서비스 등)에 알린다.</p>
 */
public enum DeliveryStatus {
    WAITING,         // 배달 대기중 (라이더 매칭 전)
    RIDER_ASSIGNED,  // 라이더 배정 완료
    PICKED_UP,       // 픽업 완료 (가게에서 음식 수령)
    DELIVERING,      // 배달 진행중
    DELIVERED,       // 배달 완료
    CANCELLED        // 배달 취소
}
