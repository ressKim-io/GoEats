package com.goeats.order.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import com.goeats.order.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 이벤트 트랜잭션 핸들러
 *
 * <p>Spring Cloud Stream 함수형 Consumer에서 @Transactional을 직접 사용할 수 없으므로
 * (Spring AOP 프록시가 함수형 빈에 적용되지 않음), 별도 @Service로 트랜잭션 로직을 분리.</p>
 *
 * <p>DB 작업(주문 상태 변경 + Saga 상태 변경 + ProcessedEvent 저장)을 하나의 트랜잭션으로 묶는다.</p>
 *
 * ★ Transactional handler separated from Consumer bean
 *   to ensure Spring AOP proxy works correctly for @Transactional
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventHandler {

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaStateRepository;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * 결제 완료 이벤트 처리 - 주문 상태를 PAID로 변경하고 Saga 진행.
     *
     * @param event 결제 완료 이벤트 (paymentId, orderId, amount, paymentMethod, eventId)
     */
    @Transactional
    public void handleCompleted(PaymentCompletedEvent event) {
        // ★ Idempotent check: skip if already processed
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentCompletedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        // 주문 상태를 PAID로 업데이트
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.PAID);
            log.info("Order {} updated to PAID", order.getId());
        });

        // ★ Saga 상태 진행: PAYMENT_COMPLETED 단계로 전진
        sagaStateRepository.findByOrderId(event.orderId())
                .ifPresent(saga -> {
                    saga.advanceStep("PAYMENT_COMPLETED");
                    log.info("Saga advanced to PAYMENT_COMPLETED for orderId={}", event.orderId());
                });

        // ★ Mark event as processed (idempotent consumer)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }

    /**
     * 결제 실패 이벤트 처리 (Saga 보상 트랜잭션).
     *
     * <p>결제가 실패하면 주문을 CANCELLED로 변경하고,
     * Saga 상태를 COMPENSATING으로 전환한다.</p>
     *
     * @param event 결제 실패 이벤트 (orderId, reason, eventId)
     */
    @Transactional
    public void handleFailed(PaymentFailedEvent event) {
        // 멱등성 체크
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentFailedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        // ★ Saga compensation: cancel order on payment failure
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.CANCELLED);
            log.warn("Order {} CANCELLED due to payment failure: {}",
                    order.getId(), event.reason());
        });

        // Saga 상태를 COMPENSATING으로 전환하고 실패 사유 기록
        sagaStateRepository.findByOrderId(event.orderId())
                .ifPresent(saga -> saga.startCompensation(event.reason()));

        // 처리 완료 기록 (멱등성)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }
}
