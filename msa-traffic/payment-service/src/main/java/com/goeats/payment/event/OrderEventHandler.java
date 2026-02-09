package com.goeats.payment.event;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.common.outbox.OutboxService;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.entity.PaymentStatus;
import com.goeats.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 이벤트 트랜잭션 핸들러
 *
 * <p>Spring Cloud Stream 함수형 Consumer에서 @Transactional을 직접 사용할 수 없으므로
 * (Spring AOP 프록시가 함수형 빈에 적용되지 않음), 별도 @Service로 트랜잭션 로직을 분리.</p>
 *
 * <p>결제 처리 + ProcessedEvent 저장 + Outbox 이벤트 저장을 하나의 트랜잭션으로 묶는다.</p>
 *
 * ★ Transactional handler separated from Consumer bean
 *   to ensure Spring AOP proxy works correctly for @Transactional
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventHandler {

    private final PaymentService paymentService;
    private final OutboxService outboxService;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * OrderCreatedEvent 처리 - 멱등성 체크 후 결제를 생성하고 Outbox에 결과 이벤트를 저장.
     *
     * @param event 주문 생성 이벤트 (orderId, totalAmount, paymentMethod, eventId 포함)
     */
    @Transactional
    public void handle(OrderCreatedEvent event) {
        // ★ Idempotent check - 이미 처리한 이벤트인지 확인 (중복 방지)
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing OrderCreatedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        try {
            // 결제 처리 (PaymentService의 이중 멱등성 체크 포함)
            Payment payment = paymentService.processPayment(
                    event.orderId(), event.totalAmount(),
                    event.paymentMethod(), event.eventId());

            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                // ★ Outbox: atomic payment result publishing
                PaymentCompletedEvent completedEvent = new PaymentCompletedEvent(
                        payment.getId(), event.orderId(),
                        event.totalAmount(), event.paymentMethod());

                outboxService.saveEvent("Payment", payment.getId().toString(),
                        "PaymentCompleted", completedEvent);

                log.info("Payment completed, outbox event saved: orderId={}", event.orderId());
            } else {
                // 결제 실패 이벤트를 Outbox에 저장 → Saga 보상 트랜잭션 트리거
                PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                        event.orderId(), "Payment processing failed");

                outboxService.saveEvent("Payment", event.orderId().toString(),
                        "PaymentFailed", failedEvent);

                log.warn("Payment failed, outbox event saved: orderId={}", event.orderId());
            }
        } catch (Exception e) {
            log.error("Payment processing error: orderId={}", event.orderId(), e);
            PaymentFailedEvent failedEvent = new PaymentFailedEvent(
                    event.orderId(), e.getMessage());
            outboxService.saveEvent("Payment", event.orderId().toString(),
                    "PaymentFailed", failedEvent);
        }

        // ★ Mark as processed - 이벤트 처리 완료 기록 (다음에 같은 이벤트가 오면 스킵됨)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }
}
