package com.goeats.delivery.entity;

/**
 * 배달 상태를 나타내는 열거형(Enum).
 *
 * <p>배달 상태 전이(State Transition):
 * WAITING → RIDER_ASSIGNED → PICKED_UP → DELIVERING → DELIVERED
 *
 * <ul>
 *   <li>WAITING → RIDER_ASSIGNED : 라이더가 배정됨 (분산 락으로 중복 배정 방지)</li>
 *   <li>RIDER_ASSIGNED → PICKED_UP : 라이더가 음식을 수령함</li>
 *   <li>PICKED_UP → DELIVERING : 배달 출발</li>
 *   <li>DELIVERING → DELIVERED : 배달 완료 (고객에게 전달)</li>
 *   <li>어느 단계에서든 → CANCELLED : 배달 취소</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 상태 변경이 같은 DB 트랜잭션에서 처리되어 일관성 보장
 * - MSA: 상태 변경 후 이벤트를 발행하여 다른 서비스에 알림
 *   → 라이더 배정 시 분산 락(Redisson)으로 동시성 제어 필요</p>
 */
public enum DeliveryStatus {
    /** 배달 대기 중 - 결제 완료 후 라이더 배정 대기 */
    WAITING,
    /** 라이더 배정 완료 - 분산 락으로 하나의 라이더만 배정됨 */
    RIDER_ASSIGNED,
    /** 음식 수령 완료 - 라이더가 가게에서 음식을 픽업함 */
    PICKED_UP,
    /** 배달 중 - 라이더가 고객에게 이동 중 */
    DELIVERING,
    /** 배달 완료 - 고객에게 음식 전달 완료 */
    DELIVERED,
    /** 배달 취소 - 주문 취소 또는 배달 불가 상황 */
    CANCELLED
}
