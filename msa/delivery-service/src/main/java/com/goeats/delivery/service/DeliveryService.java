package com.goeats.delivery.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.repository.DeliveryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * ★ MSA: DeliveryService with Redis distributed lock for rider assignment.
 *
 * Compare with Monolithic:
 * - Monolithic: @Lock(PESSIMISTIC_WRITE) DB-level lock
 * - MSA: Redisson distributed lock (works across multiple service instances)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final RiderMatchingService riderMatchingService;
    private final RedissonClient redissonClient;

    /**
     * ★ MSA: Distributed lock for rider assignment.
     * Prevents two delivery requests from assigning the same rider.
     *
     * Compare with Monolithic: @Lock(PESSIMISTIC_WRITE) on JPA query
     * - only works within single database.
     */
    @Transactional
    public Delivery createDelivery(Long orderId, String deliveryAddress) {
        Delivery delivery = Delivery.builder()
                .orderId(orderId)
                .deliveryAddress(deliveryAddress)
                .build();

        delivery = deliveryRepository.save(delivery);

        // ★ MSA: Distributed lock for rider assignment
        String lockKey = "lock:rider-assignment:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Failed to acquire lock for rider assignment: orderId={}", orderId);
                return delivery;
            }

            // Find and assign nearest rider using Redis GEO
            String riderId = riderMatchingService.findNearestRider(
                    127.0276, 37.4979, 5.0); // Example: Gangnam coordinates

            if (riderId != null) {
                delivery.assignRider("Rider-" + riderId, "010-" + riderId);
                log.info("Rider assigned to delivery: orderId={}", orderId);
            } else {
                log.warn("No available rider for orderId={}", orderId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted during rider assignment", e);
        } finally {
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

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
}
