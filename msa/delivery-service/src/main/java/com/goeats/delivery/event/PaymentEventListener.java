package com.goeats.delivery.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 결제 완료 이벤트를 수신하는 Kafka 리스너.
 *
 * <p>Saga 패턴의 세 번째 단계를 담당합니다:
 * OrderCreated → PaymentCompleted → [여기] DeliveryCreated
 *
 * 결제 서비스가 발행한 PaymentCompletedEvent를 수신하여 배달을 생성합니다.
 * 배달 생성 실패 시에는 보상 트랜잭션(DeliveryFailedEvent)을 발행하여
 * 결제 환불 및 주문 취소를 유도해야 합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: OrderService에서 deliveryService.createDelivery(order)를 직접 호출
 *   → 같은 @Transactional 안에서 실행되어 실패 시 전체 롤백
 * - MSA: Kafka를 통한 비동기 이벤트로 배달 생성이 트리거됨
 *   → 결제 서비스와 배달 서비스가 완전히 분리되어 독립 배포/스케일링 가능
 *   → 배달 서비스가 일시 장애여도 Kafka에 이벤트가 보관되어 복구 후 처리</p>
 */

/**
 * ★ MSA: Kafka listener triggers delivery creation when payment completes.
 *
 * Saga flow: OrderCreated → PaymentCompleted → DeliveryCreated
 *
 * Compare with Monolithic: deliveryService.createDelivery(order)
 * called directly in OrderService within same @Transactional.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final DeliveryService deliveryService;

    /**
     * Kafka "payment-events" 토픽에서 결제 완료 이벤트를 수신합니다.
     *
     * groupId = "delivery-service"로 설정하여 배달 서비스 인스턴스들이
     * 같은 컨슈머 그룹을 형성합니다. 하나의 이벤트는 그룹 내 하나의 인스턴스만 처리합니다.
     */
    @KafkaListener(topics = "payment-events", groupId = "delivery-service")
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received PaymentCompletedEvent: orderId={}", event.orderId());

        try {
            // 배달 생성 - 내부에서 Redisson 분산 락으로 라이더 매칭
            deliveryService.createDelivery(event.orderId(), "Default Address");
            log.info("Delivery created for order: {}", event.orderId());
        } catch (Exception e) {
            log.error("Failed to create delivery for order: {}", event.orderId(), e);
            // In production: publish DeliveryFailedEvent for saga compensation
            // 프로덕션에서는 DeliveryFailedEvent를 발행하여 결제 환불 + 주문 취소 유도
        }
    }
}
