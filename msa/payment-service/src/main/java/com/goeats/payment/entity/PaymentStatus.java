package com.goeats.payment.entity;

/**
 * 결제 상태를 나타내는 열거형(Enum).
 *
 * <p>결제 상태 전이(State Transition):
 * <ul>
 *   <li>PENDING → COMPLETED : 결제 성공 (PG사 승인 완료)</li>
 *   <li>PENDING → FAILED : 결제 실패 (잔액 부족, PG사 오류 등)</li>
 *   <li>COMPLETED → REFUNDED : 환불 처리 (주문 취소 시)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 상태 변경이 하나의 트랜잭션 안에서 보장됨
 * - MSA: 상태 변경 후 Kafka 이벤트를 발행하여 다른 서비스에 알림
 *   → 이벤트 발행이 실패하면 데이터 불일치 가능 (Saga 패턴으로 보상)</p>
 */
public enum PaymentStatus {
    /** 결제 대기 중 - 결제가 생성되었지만 아직 PG사 처리 전 */
    PENDING,
    /** 결제 완료 - PG사 승인이 성공하여 결제가 확정됨 */
    COMPLETED,
    /** 결제 실패 - PG사 승인이 거부되거나 오류 발생 */
    FAILED,
    /** 환불 완료 - 결제 완료 후 주문 취소 등으로 환불 처리됨 */
    REFUNDED
}
