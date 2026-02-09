package com.goeats.order.saga;

import com.goeats.common.command.DeliveryCommand;
import com.goeats.common.command.PaymentCommand;
import com.goeats.common.outbox.OutboxService;
import com.goeats.order.entity.*;
import com.goeats.order.event.OrderStatusPublisher;
import com.goeats.order.repository.OrderRepository;
import com.goeats.order.repository.SagaStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order Saga Orchestrator - 중앙 제어 State Machine
 *
 * <p>Orchestration Saga 패턴의 핵심 컴포넌트. 주문 생성 Saga의 전체 흐름을 제어한다.
 * 각 서비스에 Command를 발행하고, Reply를 수신하여 다음 단계를 결정한다.</p>
 *
 * <h3>Orchestration Saga 흐름</h3>
 * <pre>
 *   startSaga()
 *     → PaymentCommand(PROCESS) → Payment Service
 *
 *   handlePaymentResult(success=true)
 *     → DeliveryCommand → Delivery Service
 *
 *   handleDeliveryResult(success=true)
 *     → Saga COMPLETED
 *
 *   handlePaymentResult(success=false)
 *     → Saga FAILED (no compensation needed - nothing to undo)
 *
 *   handleDeliveryResult(success=false)
 *     → PaymentCommand(COMPENSATE) → Payment Service
 *
 *   handleCompensationResult()
 *     → Saga FAILED
 * </pre>
 *
 * <h3>★ vs Choreography</h3>
 * <pre>
 * Choreography:
 *   각 서비스가 이벤트를 독립적으로 구독/발행
 *   전체 흐름을 한 곳에서 파악 불가
 *   보상 로직이 각 서비스에 분산
 *
 * Orchestration:
 *   Orchestrator가 Command/Reply로 순차 제어
 *   전체 흐름이 이 클래스 하나에 집중
 *   보상 로직도 Orchestrator가 결정
 * </pre>
 *
 * ★★★ Central Saga Orchestrator - manages the entire order saga lifecycle
 *
 * Flow: startSaga → PaymentCommand → SagaReply → DeliveryCommand → SagaReply → Complete
 * Compensation: DeliveryFail → CompensatePayment → SagaReply → Failed
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSagaOrchestrator {

    private final SagaStateRepository sagaStateRepository;
    private final OrderRepository orderRepository;
    private final OutboxService outboxService;
    private final OrderStatusPublisher orderStatusPublisher;

    /**
     * Saga 시작 - PaymentCommand(PROCESS) 발행.
     *
     * <p>OrderService.createOrder()에서 호출. 주문과 SagaState를 같은 트랜잭션에서 생성한 후
     * Outbox에 PaymentCommand를 저장한다.</p>
     *
     * @param sagaId          Saga unique ID
     * @param order           Created order entity
     */
    @Transactional
    public void startSaga(String sagaId, Order order) {
        // PaymentCommand(PROCESS) → Outbox
        PaymentCommand command = PaymentCommand.process(
                sagaId, order.getId(),
                order.getTotalAmount(), order.getPaymentMethod());

        outboxService.saveEvent("Order", order.getId().toString(),
                "ProcessPayment", command);

        log.info("Saga started: sagaId={}, orderId={}, step=PAYMENT_PENDING",
                sagaId, order.getId());
    }

    /**
     * 결제 결과 처리.
     *
     * <p>성공: SagaStep → PAYMENT_COMPLETED, 주문 상태 → PAID, DeliveryCommand 발행.
     * 실패: SagaStep → FAILED, 주문 상태 → CANCELLED.</p>
     */
    @Transactional
    public void handlePaymentResult(String sagaId, Long orderId, boolean success,
                                     String failureReason, Long paymentId) {
        SagaState saga = sagaStateRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga not found: " + sagaId));

        if (success) {
            // Step forward: PAYMENT_PENDING → PAYMENT_COMPLETED
            saga.transitionTo(SagaStep.PAYMENT_COMPLETED);

            // Update order status to PAID
            orderRepository.findById(orderId).ifPresent(order -> {
                order.updateStatus(OrderStatus.PAID);
                orderStatusPublisher.publish(orderId, OrderStatus.PAID.name());
            });

            log.info("Payment succeeded, advancing saga: sagaId={}, orderId={}", sagaId, orderId);

            // Next step: PAYMENT_COMPLETED → DELIVERY_PENDING
            saga.transitionTo(SagaStep.DELIVERY_PENDING);

            // Find delivery address from order
            String deliveryAddress = orderRepository.findById(orderId)
                    .map(Order::getDeliveryAddress)
                    .orElse("Default Address");

            // DeliveryCommand → Outbox
            DeliveryCommand deliveryCommand = DeliveryCommand.create(
                    sagaId, orderId, deliveryAddress);

            outboxService.saveEvent("Order", orderId.toString(),
                    "CreateDelivery", deliveryCommand);

            log.info("DeliveryCommand sent: sagaId={}, orderId={}", sagaId, orderId);
        } else {
            // Payment failed → no compensation needed (nothing to undo yet)
            saga.fail(failureReason);

            orderRepository.findById(orderId).ifPresent(order -> {
                order.updateStatus(OrderStatus.CANCELLED);
                orderStatusPublisher.publish(orderId, OrderStatus.CANCELLED.name());
            });

            log.warn("Payment failed, saga failed: sagaId={}, reason={}", sagaId, failureReason);
        }
    }

    /**
     * 배달 결과 처리.
     *
     * <p>성공: Saga COMPLETED.
     * 실패: 결제 보상(환불) Command 발행.</p>
     */
    @Transactional
    public void handleDeliveryResult(String sagaId, Long orderId, boolean success,
                                      String failureReason, Long deliveryId) {
        SagaState saga = sagaStateRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga not found: " + sagaId));

        if (success) {
            // All steps completed
            saga.complete();

            orderRepository.findById(orderId).ifPresent(order -> {
                order.updateStatus(OrderStatus.DELIVERING);
                orderStatusPublisher.publish(orderId, OrderStatus.DELIVERING.name());
            });

            log.info("Saga completed: sagaId={}, orderId={}", sagaId, orderId);
        } else {
            // Delivery failed → compensate payment
            saga.startCompensation(failureReason);

            // CompensatePaymentCommand → Outbox
            PaymentCommand compensateCommand = PaymentCommand.compensate(
                    sagaId, orderId, failureReason);

            outboxService.saveEvent("Order", orderId.toString(),
                    "CompensatePayment", compensateCommand);

            log.warn("Delivery failed, compensating payment: sagaId={}, reason={}",
                    sagaId, failureReason);
        }
    }

    /**
     * 보상 결과 처리.
     *
     * <p>결제 환불 완료 후 Saga를 최종 FAILED로 전환.</p>
     */
    @Transactional
    public void handleCompensationResult(String sagaId, Long orderId, boolean success) {
        SagaState saga = sagaStateRepository.findBySagaId(sagaId)
                .orElseThrow(() -> new IllegalStateException("Saga not found: " + sagaId));

        saga.fail(saga.getFailureReason());

        orderRepository.findById(orderId).ifPresent(order -> {
            order.updateStatus(OrderStatus.CANCELLED);
            orderStatusPublisher.publish(orderId, OrderStatus.CANCELLED.name());
        });

        log.info("Compensation completed, saga failed: sagaId={}, orderId={}", sagaId, orderId);
    }
}
