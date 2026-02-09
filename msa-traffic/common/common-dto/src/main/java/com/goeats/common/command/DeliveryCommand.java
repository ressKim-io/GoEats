package com.goeats.common.command;

import java.util.UUID;

/**
 * Delivery Command - Orchestration Saga Command DTO
 *
 * <p>Order Orchestrator가 Delivery Service에게 전송하는 커맨드.
 * 결제 성공 후 Orchestrator가 명시적으로 배달 생성을 지시한다.</p>
 *
 * <h3>Orchestration vs Choreography</h3>
 * <pre>
 * Choreography (MSA Basic):
 *   PaymentCompletedEvent → Delivery Service (독립적으로 구독)
 *   Delivery가 이벤트를 보고 스스로 배달 생성
 *
 * Orchestration (MSA Traffic):
 *   Orchestrator → DeliveryCommand → Delivery Service (명령 수신)
 *   Delivery는 커맨드에 따라 배달을 생성하고 SagaReply로 결과 회신
 * </pre>
 *
 * ★ Orchestration Saga: explicit command from Orchestrator to Delivery Service
 */
public record DeliveryCommand(
        String eventId,          // Idempotent key (UUID)
        String sagaId,           // Saga correlation ID
        Long orderId,            // Order ID
        String deliveryAddress   // Delivery address
) {
    /** Create a delivery command */
    public static DeliveryCommand create(String sagaId, Long orderId, String deliveryAddress) {
        return new DeliveryCommand(
                UUID.randomUUID().toString(), sagaId, orderId, deliveryAddress);
    }
}
