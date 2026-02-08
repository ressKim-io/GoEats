package com.goeats.payment.entity;

/**
 * 결제 상태 열거형.
 *
 * <p>결제의 전체 생명주기를 나타내는 상태 값을 정의한다.</p>
 *
 * <h3>상태 전이 흐름</h3>
 * <pre>
 *   PENDING ──→ COMPLETED ──→ REFUNDED
 *      │
 *      └──→ FAILED
 * </pre>
 *
 * <ul>
 *   <li><b>PENDING</b> - 결제 생성 직후 초기 상태. PG사 승인 대기 중.</li>
 *   <li><b>COMPLETED</b> - PG사 승인 완료. 정상 결제 상태.</li>
 *   <li><b>FAILED</b> - PG사 승인 실패. 잔액 부족, 카드 오류 등.</li>
 *   <li><b>REFUNDED</b> - 환불 완료. Saga 보상 트랜잭션 또는 수동 환불.</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic과 동일한 상태값을 사용한다. 다만 Traffic에서는 Saga State 추적 엔티티와
 * 연계하여 주문-결제-배달 전체 흐름의 상태를 통합 관리한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서도 동일한 상태 열거형을 사용하지만, 상태 전이가 같은 트랜잭션 내에서
 * 동기적으로 처리되었다. MSA에서는 Kafka 이벤트를 통해 비동기적으로 상태가 전이되므로,
 * 중간 상태(PENDING)에서 머무는 시간이 더 길 수 있다.</p>
 */
public enum PaymentStatus {
    /** 결제 대기 - PG사 승인 요청 전 초기 상태 */
    PENDING,

    /** 결제 완료 - PG사 승인 성공 */
    COMPLETED,

    /** 결제 실패 - PG사 승인 실패 */
    FAILED,

    /** 환불 완료 - Saga 보상 트랜잭션 또는 수동 환불 */
    REFUNDED
}
