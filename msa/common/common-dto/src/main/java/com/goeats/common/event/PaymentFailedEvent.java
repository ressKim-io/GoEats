package com.goeats.common.event;

/**
 * 결제 실패 이벤트 - Saga 보상 트랜잭션(Compensating Transaction)을 트리거하는 이벤트.
 *
 * <p>결제가 실패하면 이 이벤트가 발행되고, 주문 서비스가 이를 소비하여
 * 주문 상태를 CANCELLED로 변경한다. 이것이 Saga의 보상(rollback) 단계이다.</p>
 *
 * <p>Saga 보상 트랜잭션이 필요한 이유:
 * MSA에서는 분산 트랜잭션(2PC)을 사용하지 않는다. 대신 각 서비스가 로컬 트랜잭션만 수행하고,
 * 실패 시 이전 단계의 작업을 취소하는 보상 이벤트를 발행한다.
 * 예: 주문 생성(성공) -> 결제(실패) -> 주문 취소(보상)</p>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 결제 실패 시 {@code @Transactional}이
 * 자동으로 DB 롤백을 수행한다. 별도의 보상 로직이 필요 없다.
 * MSA에서는 주문 서비스의 DB와 결제 서비스의 DB가 분리되어 있으므로,
 * 이벤트를 통한 명시적 보상 처리가 필수적이다.</p>
 */
public record PaymentFailedEvent(
    Long orderId,  // 주문 ID - 어떤 주문의 결제가 실패했는지 식별
    String reason  // 실패 사유 - 사용자에게 안내하거나 로그에 기록
) {}
