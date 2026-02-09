package com.goeats.common.command;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment Command - Orchestration Saga Command DTO
 *
 * <p>Order Orchestrator가 Payment Service에게 전송하는 커맨드.
 * Choreography와 달리, Orchestrator가 명시적으로 "결제 처리" 또는 "결제 보상"을 지시한다.</p>
 *
 * <h3>Command 유형</h3>
 * <ul>
 *   <li>PROCESS: 결제 진행 (Saga 정방향)</li>
 *   <li>COMPENSATE: 결제 환불 (Saga 보상 트랜잭션)</li>
 * </ul>
 *
 * <h3>Orchestration vs Choreography</h3>
 * <pre>
 * Choreography (MSA Basic):
 *   Order → OrderCreatedEvent → Payment (독립적으로 구독)
 *   Payment가 스스로 판단하여 결제 처리
 *
 * Orchestration (MSA Traffic):
 *   Orchestrator → PaymentCommand(PROCESS) → Payment (명령 수신)
 *   Payment은 커맨드에 따라 처리하고 SagaReply로 결과 회신
 * </pre>
 *
 * ★ Orchestration Saga: explicit command from Orchestrator to Payment Service
 *   PROCESS → process payment, COMPENSATE → refund payment
 */
public record PaymentCommand(
        String eventId,          // Idempotent key (UUID)
        String sagaId,           // Saga correlation ID
        Long orderId,            // Order ID
        CommandType commandType, // PROCESS or COMPENSATE
        BigDecimal amount,       // Payment amount
        String paymentMethod,    // Payment method (CARD, CASH, etc.)
        String reason            // Compensation reason (null for PROCESS)
) {
    public enum CommandType {
        PROCESS,      // Process payment (forward step)
        COMPENSATE    // Refund payment (compensation step)
    }

    /** Create a PROCESS command */
    public static PaymentCommand process(String sagaId, Long orderId,
                                         BigDecimal amount, String paymentMethod) {
        return new PaymentCommand(
                UUID.randomUUID().toString(), sagaId, orderId,
                CommandType.PROCESS, amount, paymentMethod, null);
    }

    /** Create a COMPENSATE command */
    public static PaymentCommand compensate(String sagaId, Long orderId, String reason) {
        return new PaymentCommand(
                UUID.randomUUID().toString(), sagaId, orderId,
                CommandType.COMPENSATE, null, null, reason);
    }
}
