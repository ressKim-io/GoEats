package com.goeats.common.event;

import java.util.UUID;

/**
 * 결제 실패 이벤트 (Payment Failed Event)
 *
 * <p>결제 서비스에서 결제가 실패했을 때 발행하는 이벤트 DTO.
 * Saga 패턴에서 "보상 트랜잭션(Compensation)"을 트리거하는 핵심 이벤트.</p>
 *
 * <h3>보상 트랜잭션 흐름 (Saga Compensation)</h3>
 * <pre>
 *   Payment Service (결제 실패)
 *     → [Kafka: payment-failed-events]
 *       → Order Service (주문 상태 → CANCELLED, 보상 트랜잭션 실행)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 @Transactional이 자동 롤백 처리.
 * PaymentService.pay()에서 예외 발생 시 OrderService의 변경도 함께 롤백됨.
 * 보상 트랜잭션이라는 개념 자체가 불필요.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서도 보상 이벤트를 발행하지만, 아래 문제가 있음:</p>
 * <ul>
 *   <li><b>이벤트 유실</b>: Kafka 발행 실패 시 주문이 영원히 PENDING 상태에 머무름</li>
 *   <li><b>중복 보상</b>: 같은 실패 이벤트가 2번 소비되면 이미 취소된 주문을 다시 취소 시도</li>
 * </ul>
 * <p>MSA-Traffic에서는:</p>
 * <ul>
 *   <li><b>Outbox 패턴</b>: 결제 실패 기록과 같은 트랜잭션으로 이벤트 저장 → 유실 방지</li>
 *   <li><b>eventId</b>: Idempotent Consumer로 보상 트랜잭션이 정확히 1번만 실행됨</li>
 *   <li><b>SagaState 추적</b>: 주문 서비스에서 Saga 상태를 기록하여 현재 단계 파악 가능</li>
 * </ul>
 *
 * ★ Traffic MSA: eventId added for idempotent saga compensation.
 * Ensures order cancellation happens exactly once on payment failure.
 */
public record PaymentFailedEvent(
        String eventId,  // 멱등성 보장을 위한 이벤트 고유 ID (UUID)
        Long orderId,    // 취소 대상 주문 ID (Saga correlation key)
        String reason    // 결제 실패 사유 (잔액 부족, PG사 오류 등)
) {
    /**
     * eventId 자동 생성 편의 생성자.
     * Payment Service에서 결제 실패 시 보상 이벤트 생성에 사용.
     */
    public PaymentFailedEvent(Long orderId, String reason) {
        this(UUID.randomUUID().toString(), orderId, reason);
    }
}
