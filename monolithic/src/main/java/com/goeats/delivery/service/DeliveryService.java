package com.goeats.delivery.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.repository.DeliveryRepository;
import com.goeats.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★ Monolithic: DeliveryService is called directly by OrderService.
 * Rider matching is a simple in-memory simulation.
 *
 * Compare with MSA: DeliveryService is a separate microservice with:
 * - Redis GEO for real-time rider location tracking
 * - Kafka event listener for PaymentCompletedEvent
 * - Independent delivery_db
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;

    @Transactional
    public Delivery createDelivery(Order order) {
        Delivery delivery = Delivery.builder()
                .order(order)
                .deliveryAddress(order.getDeliveryAddress())
                .build();

        delivery = deliveryRepository.save(delivery);
        log.info("Delivery created for order: {}", order.getId());

        // Simulate rider matching
        assignRider(delivery);

        return delivery;
    }

    public Delivery getDelivery(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }

    @Transactional
    public Delivery updateStatus(Long deliveryId, String action) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));

        switch (action) {
            case "pickup" -> delivery.pickUp();
            case "deliver" -> delivery.startDelivery();
            case "complete" -> delivery.complete();
            case "cancel" -> delivery.cancel();
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return delivery;
    }

    private void assignRider(Delivery delivery) {
        // Simple simulation - in MSA this would use Redis GEO for nearby riders
        delivery.assignRider("Rider Kim", "010-1234-5678");
        log.info("Rider assigned to delivery: {}", delivery.getId());
    }
}
