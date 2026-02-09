package com.goeats.payment.command;

import com.goeats.common.command.PaymentCommand;
import com.goeats.common.command.SagaReply;
import com.goeats.common.outbox.OutboxService;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.entity.PaymentStatus;
import com.goeats.payment.event.ProcessedEvent;
import com.goeats.payment.event.ProcessedEventRepository;
import com.goeats.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment Command Handler - Orchestration 커맨드 처리 + SagaReply 발행
 *
 * <p>PaymentCommand를 수신하여 commandType에 따라 결제 처리(PROCESS) 또는
 * 결제 보상(COMPENSATE)을 수행하고, 결과를 SagaReply로 Orchestrator에 회신한다.</p>
 *
 * <h3>커맨드 처리 흐름</h3>
 * <pre>
 * PROCESS:
 *   1. 멱등성 체크 (ProcessedEvent)
 *   2. PaymentService.processPayment() 호출
 *   3. SagaReply(PAYMENT, success/failure) → Outbox
 *
 * COMPENSATE:
 *   1. 멱등성 체크
 *   2. PaymentService.refund() 호출
 *   3. SagaReply(PAYMENT_COMPENSATE, success) → Outbox
 * </pre>
 *
 * <h3>★ vs Choreography (OrderEventHandler)</h3>
 * <pre>
 * Choreography:
 *   OrderCreatedEvent 수신 → 결제 처리 → PaymentCompletedEvent/PaymentFailedEvent 발행
 *   자체적으로 이벤트 유형을 결정
 *
 * Orchestration:
 *   PaymentCommand 수신 → PROCESS/COMPENSATE 분기 → 통합 SagaReply 회신
 *   Orchestrator가 다음 단계를 결정
 * </pre>
 *
 * ★ Replaces OrderEventHandler (Choreography)
 *   Command-driven: PROCESS or COMPENSATE, replies with SagaReply
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCommandHandler {

    private final PaymentService paymentService;
    private final OutboxService outboxService;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * PaymentCommand 처리 - commandType에 따라 분기.
     */
    @Transactional
    public void handle(PaymentCommand command) {
        // ★ Idempotent check
        if (processedEventRepository.existsById(command.eventId())) {
            log.info("Duplicate command skipped: eventId={}", command.eventId());
            return;
        }

        log.info("Processing PaymentCommand: sagaId={}, type={}, orderId={}",
                command.sagaId(), command.commandType(), command.orderId());

        switch (command.commandType()) {
            case PROCESS -> handleProcess(command);
            case COMPENSATE -> handleCompensate(command);
        }

        // ★ Mark as processed
        processedEventRepository.save(new ProcessedEvent(command.eventId()));
    }

    private void handleProcess(PaymentCommand command) {
        try {
            Payment payment = paymentService.processPayment(
                    command.orderId(), command.amount(),
                    command.paymentMethod(), command.eventId());

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                // Success reply
                SagaReply reply = SagaReply.success(
                        command.sagaId(), command.orderId(),
                        SagaReply.StepName.PAYMENT, payment.getId());

                outboxService.saveEvent("Payment", payment.getId().toString(),
                        "SagaReply", reply);

                log.info("Payment processed, reply sent: orderId={}, paymentId={}",
                        command.orderId(), payment.getId());
            } else {
                // Failure reply
                SagaReply reply = SagaReply.failure(
                        command.sagaId(), command.orderId(),
                        SagaReply.StepName.PAYMENT, "Payment processing failed");

                outboxService.saveEvent("Payment", command.orderId().toString(),
                        "SagaReply", reply);

                log.warn("Payment failed, reply sent: orderId={}", command.orderId());
            }
        } catch (Exception e) {
            log.error("Payment error: orderId={}", command.orderId(), e);

            SagaReply reply = SagaReply.failure(
                    command.sagaId(), command.orderId(),
                    SagaReply.StepName.PAYMENT, e.getMessage());

            outboxService.saveEvent("Payment", command.orderId().toString(),
                    "SagaReply", reply);
        }
    }

    private void handleCompensate(PaymentCommand command) {
        try {
            paymentService.refund(command.orderId());

            SagaReply reply = SagaReply.success(
                    command.sagaId(), command.orderId(),
                    SagaReply.StepName.PAYMENT_COMPENSATE, null);

            outboxService.saveEvent("Payment", command.orderId().toString(),
                    "SagaReply", reply);

            log.info("Payment compensated (refunded): orderId={}", command.orderId());
        } catch (Exception e) {
            log.error("Payment compensation error: orderId={}", command.orderId(), e);

            SagaReply reply = SagaReply.failure(
                    command.sagaId(), command.orderId(),
                    SagaReply.StepName.PAYMENT_COMPENSATE, e.getMessage());

            outboxService.saveEvent("Payment", command.orderId().toString(),
                    "SagaReply", reply);
        }
    }
}
