package com.goeats.order.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.common.event.PaymentFailedEvent;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kafka 이벤트 리스너: 결제 서비스(PaymentService)의 처리 결과를 수신합니다.
 *
 * <p>이 클래스는 Saga 패턴의 핵심 구성요소입니다. MSA에서는 서비스 간에
 * 단일 트랜잭션(@Transactional)을 사용할 수 없으므로, 이벤트를 통해
 * 서비스 간 트랜잭션을 조율합니다.</p>
 *
 * <p>수신하는 이벤트:</p>
 * <ul>
 *   <li>{@code PaymentCompletedEvent} - 결제 성공 → 주문 상태를 PAID로 변경</li>
 *   <li>{@code PaymentFailedEvent} - 결제 실패 → 주문 상태를 CANCELLED로 변경 (보상 트랜잭션)</li>
 * </ul>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 이벤트 리스너가 필요 없습니다.
 * {@code paymentService.processPayment()}의 반환값으로 결제 결과를 동기적으로 받고,
 * 같은 트랜잭션 안에서 주문 상태를 바로 업데이트합니다.</p>
 *
 * ★ MSA: Kafka event listener for saga coordination.
 *
 * Listens for payment results from PaymentService:
 * - PaymentCompletedEvent → update order to PAID
 * - PaymentFailedEvent → update order to CANCELLED (saga compensation)
 *
 * Compare with Monolithic: No event listeners needed.
 * Payment result is returned synchronously from paymentService.processPayment().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final OrderRepository orderRepository;

    // "payment-events" 토픽을 구독하여 결제 완료 이벤트를 수신
    // groupId: 같은 그룹의 컨슈머끼리 파티션을 분배하여 병렬 처리
    @KafkaListener(topics = "payment-events", groupId = "order-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent: orderId={}", event.orderId());
        // 결제 성공 → 주문 상태를 PAID로 업데이트
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            order.updateStatus(OrderStatus.PAID);
            log.info("Order {} updated to PAID", order.getId());
        });
    }

    // "payment-failed-events" 토픽을 구독하여 결제 실패 이벤트를 수신
    @KafkaListener(topics = "payment-failed-events", groupId = "order-service")
    @Transactional
    public void handlePaymentFailed(PaymentFailedEvent event) {
        log.info("Received PaymentFailedEvent: orderId={}, reason={}",
                event.orderId(), event.reason());
        orderRepository.findById(event.orderId()).ifPresent(order -> {
            // ★ MSA Saga compensation: cancel order when payment fails
            // ★ Saga 보상 트랜잭션: 결제 실패 시 주문을 취소하여 데이터 정합성을 유지
            // Monolithic에서는 @Transactional 롤백으로 처리되지만,
            // MSA에서는 이미 커밋된 주문을 명시적으로 취소해야 함
            order.updateStatus(OrderStatus.CANCELLED);
            log.warn("Order {} CANCELLED due to payment failure: {}",
                    order.getId(), event.reason());
        });
    }
}
