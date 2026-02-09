package com.goeats.common.command;

import java.util.UUID;

/**
 * Saga Reply - Orchestration Saga Reply DTO
 *
 * <p>Payment/Delivery Service가 Orchestrator에게 커맨드 처리 결과를 회신하는 통합 DTO.
 * 하나의 saga-replies 토픽을 사용하며, stepName으로 어떤 단계의 결과인지 구분한다.</p>
 *
 * <h3>Orchestration 흐름</h3>
 * <pre>
 *   Orchestrator → PaymentCommand → Payment Service
 *                                     └→ SagaReply(PAYMENT, success=true) → Orchestrator
 *   Orchestrator → DeliveryCommand → Delivery Service
 *                                     └→ SagaReply(DELIVERY, success=true) → Orchestrator
 * </pre>
 *
 * <h3>실패 시 보상 흐름</h3>
 * <pre>
 *   Delivery Service → SagaReply(DELIVERY, success=false) → Orchestrator
 *   Orchestrator → PaymentCommand(COMPENSATE) → Payment Service
 *                                                 └→ SagaReply(PAYMENT_COMPENSATE, success=true) → Orchestrator
 * </pre>
 *
 * <h3>stepName 종류</h3>
 * <ul>
 *   <li>PAYMENT: 결제 처리 결과</li>
 *   <li>DELIVERY: 배달 생성 결과</li>
 *   <li>PAYMENT_COMPENSATE: 결제 보상(환불) 결과</li>
 * </ul>
 *
 * ★ Unified reply DTO for all saga steps
 *   Single saga-replies topic, distinguished by stepName
 */
public record SagaReply(
        String eventId,          // Idempotent key (UUID)
        String sagaId,           // Saga correlation ID
        Long orderId,            // Order ID
        StepName stepName,       // Which saga step this reply is for
        boolean success,         // Whether the step succeeded
        String failureReason,    // Failure reason (null if success)
        Long resultId            // Result entity ID (paymentId or deliveryId)
) {
    public enum StepName {
        PAYMENT,              // Payment processing result
        DELIVERY,             // Delivery creation result
        PAYMENT_COMPENSATE    // Payment compensation (refund) result
    }

    /** Create a success reply */
    public static SagaReply success(String sagaId, Long orderId,
                                    StepName stepName, Long resultId) {
        return new SagaReply(
                UUID.randomUUID().toString(), sagaId, orderId,
                stepName, true, null, resultId);
    }

    /** Create a failure reply */
    public static SagaReply failure(String sagaId, Long orderId,
                                    StepName stepName, String reason) {
        return new SagaReply(
                UUID.randomUUID().toString(), sagaId, orderId,
                stepName, false, reason, null);
    }
}
