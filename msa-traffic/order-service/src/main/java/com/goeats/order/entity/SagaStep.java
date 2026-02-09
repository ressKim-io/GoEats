package com.goeats.order.entity;

import java.util.Set;

/**
 * Saga Step - Orchestration Saga의 세부 진행 단계
 *
 * <h3>상태 전이 다이어그램</h3>
 * <pre>
 * 정상 흐름 (Happy Path):
 *   PAYMENT_PENDING → PAYMENT_COMPLETED → DELIVERY_PENDING → COMPLETED
 *
 * 배달 실패 → 보상 흐름:
 *   DELIVERY_PENDING → COMPENSATING_PAYMENT → FAILED
 *
 * 결제 실패 → 즉시 실패:
 *   PAYMENT_PENDING → FAILED
 * </pre>
 *
 * <h3>★ vs Choreography (기존 String currentStep)</h3>
 * <p>Choreography에서는 currentStep이 단순 String ("ORDER_CREATED", "PAYMENT_COMPLETED")으로
 * 전이 검증이 없었다. Orchestration에서는 enum으로 명확한 상태 머신을 정의하고,
 * canTransitionTo()로 유효하지 않은 전이를 런타임에 방지한다.</p>
 *
 * ★ Orchestration Saga Step enum with transition validation
 *   Replaces free-form String currentStep with type-safe state machine
 */
public enum SagaStep {

    /** Orchestrator가 PaymentCommand(PROCESS)를 발행한 상태 */
    PAYMENT_PENDING(Set.of("PAYMENT_COMPLETED", "FAILED")),

    /** Payment 성공 Reply 수신, DeliveryCommand 발행 대기 */
    PAYMENT_COMPLETED(Set.of("DELIVERY_PENDING")),

    /** Orchestrator가 DeliveryCommand를 발행한 상태 */
    DELIVERY_PENDING(Set.of("COMPLETED", "COMPENSATING_PAYMENT")),

    /** Delivery 실패로 Payment 보상(환불) 진행 중 */
    COMPENSATING_PAYMENT(Set.of("FAILED")),

    /** 모든 단계 성공 완료 */
    COMPLETED(Set.of()),

    /** Saga 최종 실패 (보상 완료 포함) */
    FAILED(Set.of());

    private final Set<String> validTransitions;

    SagaStep(Set<String> validTransitions) {
        this.validTransitions = validTransitions;
    }

    /** Check if transition to the target step is valid */
    public boolean canTransitionTo(SagaStep target) {
        return validTransitions.contains(target.name());
    }
}
