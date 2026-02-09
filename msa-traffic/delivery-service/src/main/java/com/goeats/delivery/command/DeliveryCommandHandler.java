package com.goeats.delivery.command;

import com.goeats.common.command.DeliveryCommand;
import com.goeats.common.command.SagaReply;
import com.goeats.common.outbox.OutboxService;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.event.ProcessedEvent;
import com.goeats.delivery.event.ProcessedEventRepository;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery Command Handler - Orchestration 커맨드 처리 + SagaReply 발행
 *
 * <p>DeliveryCommand를 수신하여 배달을 생성하고,
 * 결과를 SagaReply로 Orchestrator에 회신한다.</p>
 *
 * <h3>커맨드 처리 흐름</h3>
 * <pre>
 *   1. 멱등성 체크 (ProcessedEvent)
 *   2. DeliveryService.createDelivery() 호출
 *      (Fencing Token + 분산 락 + Bulkhead 적용)
 *   3. SagaReply(DELIVERY, success/failure) → Outbox
 * </pre>
 *
 * <h3>★ vs Choreography (PaymentEventHandler)</h3>
 * <pre>
 * Choreography:
 *   PaymentCompletedEvent 수신 → 배달 생성
 *   결과를 별도로 보고하지 않음 (Orchestrator 없음)
 *
 * Orchestration:
 *   DeliveryCommand 수신 → 배달 생성 → SagaReply 회신
 *   Orchestrator가 결과를 받고 다음 단계 결정
 * </pre>
 *
 * ★ Replaces PaymentEventHandler (Choreography)
 *   Command-driven delivery creation with SagaReply response
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryCommandHandler {

    private final DeliveryService deliveryService;
    private final OutboxService outboxService;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * DeliveryCommand 처리 - 배달 생성 후 SagaReply 발행.
     */
    @Transactional
    public void handle(DeliveryCommand command) {
        // ★ Idempotent check
        if (processedEventRepository.existsById(command.eventId())) {
            log.info("Duplicate command skipped: eventId={}", command.eventId());
            return;
        }

        log.info("Processing DeliveryCommand: sagaId={}, orderId={}",
                command.sagaId(), command.orderId());

        try {
            // DeliveryService includes Fencing Token + Distributed Lock + Bulkhead
            Delivery delivery = deliveryService.createDelivery(
                    command.orderId(), command.deliveryAddress());

            // Success reply
            SagaReply reply = SagaReply.success(
                    command.sagaId(), command.orderId(),
                    SagaReply.StepName.DELIVERY, delivery.getId());

            outboxService.saveEvent("Delivery", delivery.getId().toString(),
                    "SagaReply", reply);

            log.info("Delivery created, reply sent: orderId={}, deliveryId={}",
                    command.orderId(), delivery.getId());
        } catch (Exception e) {
            log.error("Delivery creation error: orderId={}", command.orderId(), e);

            // Failure reply → Orchestrator will compensate payment
            SagaReply reply = SagaReply.failure(
                    command.sagaId(), command.orderId(),
                    SagaReply.StepName.DELIVERY, e.getMessage());

            outboxService.saveEvent("Delivery", command.orderId().toString(),
                    "SagaReply", reply);
        }

        // ★ Mark as processed
        processedEventRepository.save(new ProcessedEvent(command.eventId()));
    }
}
