package com.goeats.delivery.event;

import com.goeats.common.event.PaymentCompletedEvent;
import com.goeats.delivery.service.DeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 완료 이벤트 트랜잭션 핸들러
 *
 * <p>Spring Cloud Stream 함수형 Consumer에서 @Transactional을 직접 사용할 수 없으므로
 * (Spring AOP 프록시가 함수형 빈에 적용되지 않음), 별도 @Service로 트랜잭션 로직을 분리.</p>
 *
 * <p>멱등성 체크 + 배달 생성을 하나의 트랜잭션으로 묶는다.</p>
 *
 * ★ Transactional handler separated from Consumer bean
 *   to ensure Spring AOP proxy works correctly for @Transactional
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentEventHandler {

    private final DeliveryService deliveryService;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * PaymentCompletedEvent 처리 - 멱등성 체크 후 배달을 생성.
     *
     * @param event 결제 완료 이벤트 (paymentId, orderId, amount, paymentMethod, eventId)
     */
    @Transactional
    public void handle(PaymentCompletedEvent event) {
        // ★ Idempotent check: 이미 처리된 이벤트인지 확인 (중복 수신 방지)
        if (processedEventRepository.existsById(event.eventId())) {
            log.info("Duplicate event skipped: eventId={}", event.eventId());
            return;
        }

        log.info("Processing PaymentCompletedEvent: orderId={}, eventId={}",
                event.orderId(), event.eventId());

        // 배달 생성 + 라이더 매칭 시도
        deliveryService.createDelivery(event.orderId(), "Default Address");
        log.info("Delivery created for order: {}", event.orderId());

        // ★ Mark as processed: 처리 완료 기록 (다음 중복 수신 시 스킵하기 위해)
        processedEventRepository.save(new ProcessedEvent(event.eventId()));
    }
}
