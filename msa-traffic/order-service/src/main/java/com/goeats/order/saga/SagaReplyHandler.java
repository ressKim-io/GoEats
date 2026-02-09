package com.goeats.order.saga;

import com.goeats.common.command.SagaReply;
import com.goeats.order.event.ProcessedEvent;
import com.goeats.order.event.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saga Reply Handler - Orchestration Reply 라우팅 및 멱등성 처리
 *
 * <p>SagaReplyListener에서 수신한 SagaReply를 stepName에 따라
 * OrderSagaOrchestrator의 적절한 메서드로 라우팅한다.</p>
 *
 * <h3>책임</h3>
 * <ol>
 *   <li><b>멱등성 체크</b>: ProcessedEvent 테이블로 중복 Reply 방지</li>
 *   <li><b>stepName 라우팅</b>: PAYMENT → handlePaymentResult, DELIVERY → handleDeliveryResult</li>
 *   <li><b>트랜잭션 관리</b>: 함수형 Consumer 빈에서 @Transactional 사용 불가 → @Service로 분리</li>
 * </ol>
 *
 * <h3>★ vs Choreography (PaymentEventHandler)</h3>
 * <p>Choreography에서는 PaymentCompletedEvent, PaymentFailedEvent 각각에 대해
 * 별도의 핸들러 메서드가 있었다. Orchestration에서는 통합 SagaReply를 stepName으로 라우팅.</p>
 *
 * ★ Transactional handler for SagaReply - routes by stepName to Orchestrator
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SagaReplyHandler {

    private final OrderSagaOrchestrator orchestrator;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * SagaReply 처리 - 멱등성 체크 후 stepName에 따라 Orchestrator로 라우팅.
     */
    @Transactional
    public void handle(SagaReply reply) {
        // ★ Idempotent check
        if (processedEventRepository.existsById(reply.eventId())) {
            log.info("Duplicate SagaReply skipped: eventId={}", reply.eventId());
            return;
        }

        log.info("Processing SagaReply: sagaId={}, step={}, success={}, orderId={}",
                reply.sagaId(), reply.stepName(), reply.success(), reply.orderId());

        // ★ Route by stepName
        switch (reply.stepName()) {
            case PAYMENT -> orchestrator.handlePaymentResult(
                    reply.sagaId(), reply.orderId(),
                    reply.success(), reply.failureReason(), reply.resultId());

            case DELIVERY -> orchestrator.handleDeliveryResult(
                    reply.sagaId(), reply.orderId(),
                    reply.success(), reply.failureReason(), reply.resultId());

            case PAYMENT_COMPENSATE -> orchestrator.handleCompensationResult(
                    reply.sagaId(), reply.orderId(), reply.success());
        }

        // ★ Mark as processed
        processedEventRepository.save(new ProcessedEvent(reply.eventId()));
    }
}
