package com.goeats.delivery.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.entity.DeliveryStatus;
import com.goeats.delivery.repository.DeliveryRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * ★★★ Traffic MSA: Delivery Service with Fencing Token + Bulkhead
 *
 * vs Basic MSA:
 *   - Simple Redisson lock → no fencing token → stale lock risk
 *   - No thread isolation → cascading failure risk
 *
 * Traffic MSA:
 *   1. Fencing Token: monotonically increasing counter in Redis
 *      prevents stale lock holders from overwriting newer data
 *   2. @Bulkhead: limits concurrent rider assignments to prevent
 *      thread pool exhaustion under traffic spikes
 *
 * Fencing Token Flow:
 *   Thread A: acquires lock, gets token=5
 *   Thread A: GC pause (lock expires)
 *   Thread B: acquires lock, gets token=6, writes to DB (token=6)
 *   Thread A: resumes, tries to write with token=5
 *   DB: rejects because 5 < 6 (stale!)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final RiderMatchingService riderMatchingService;
    private final RedissonClient redissonClient;

    @Transactional
    @Bulkhead(name = "riderAssignment")
    public Delivery createDelivery(Long orderId, String deliveryAddress) {
        Delivery delivery = Delivery.builder()
                .orderId(orderId)
                .deliveryAddress(deliveryAddress)
                .build();
        delivery = deliveryRepository.save(delivery);

        // ★ Fencing Token: acquire monotonically increasing token
        RAtomicLong fencingCounter = redissonClient.getAtomicLong("fencing:rider:" + orderId);
        long fencingToken = fencingCounter.incrementAndGet();

        String lockKey = "lock:rider-assignment:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Failed to acquire lock for rider assignment: orderId={}", orderId);
                return delivery;
            }

            String riderId = riderMatchingService.findNearestRider(
                    127.0276, 37.4979, 5.0);

            if (riderId != null) {
                // ★ Fencing Token write: DB rejects if token is stale
                int updated = deliveryRepository.updateWithFencingToken(
                        delivery.getId(),
                        "Rider-" + riderId,
                        "010-" + riderId,
                        fencingToken);

                if (updated == 0) {
                    log.warn("Stale fencing token detected: orderId={}, token={}",
                            orderId, fencingToken);
                    throw new BusinessException(ErrorCode.STALE_LOCK_DETECTED);
                }

                log.info("Rider assigned with fencing token: orderId={}, token={}",
                        orderId, fencingToken);
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

        return deliveryRepository.findById(delivery.getId()).orElse(delivery);
    }

    @Transactional
    @Bulkhead(name = "deliveryStatusUpdate")
    public Delivery updateDeliveryStatus(Long deliveryId, DeliveryStatus status) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
        delivery.updateStatus(status);
        return delivery;
    }

    public Delivery getDelivery(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }

    public Delivery getDeliveryByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }
}
