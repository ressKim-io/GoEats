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
 * 배달 핵심 비즈니스 로직을 담당하는 서비스.
 *
 * <p>Redisson 분산 락(Distributed Lock)을 사용하여 라이더 배정의 동시성을 제어합니다.
 * 여러 배달 서비스 인스턴스가 동시에 같은 라이더를 배정하려는 상황을 방지합니다.</p>
 *
 * <p>배달 처리 흐름:
 * 1. Kafka에서 PaymentCompletedEvent 수신
 * 2. createDelivery() 호출 → 배달 엔티티 생성
 * 3. Redisson 분산 락 획득 → RiderMatchingService로 가까운 라이더 매칭
 * 4. 라이더 배정 후 락 해제</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: @Lock(PESSIMISTIC_WRITE) DB 비관적 락 → 단일 DB 내에서만 동작
 * - MSA: Redisson 분산 락 → Redis를 통해 여러 서비스 인스턴스 간 동시성 제어
 *   → 서비스 인스턴스가 3개여도 하나의 라이더에 하나의 배달만 배정됨</p>
 */

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
    private final RedissonClient redissonClient;  // Redis 기반 분산 락 클라이언트

    /**
     * 배달을 생성하고 라이더를 배정합니다.
     *
     * Redisson 분산 락으로 라이더 배정의 원자성을 보장합니다.
     * tryLock(5, 3, SECONDS): 최대 5초 대기, 3초 후 자동 해제
     * → 락 획득 실패 시에도 서비스가 블로킹되지 않도록 타임아웃 설정
     */

    /**
     * ★ MSA: Distributed lock for rider assignment.
     * Prevents two delivery requests from assigning the same rider.
     *
     * Compare with Monolithic: @Lock(PESSIMISTIC_WRITE) on JPA query
     * - only works within single database.
     */
    @Transactional
    public Delivery createDelivery(Long orderId, String deliveryAddress) {
        // 배달 엔티티 생성 (초기 상태: WAITING)
        Delivery delivery = Delivery.builder()
                .orderId(orderId)
                .deliveryAddress(deliveryAddress)
                .build();

        delivery = deliveryRepository.save(delivery);

        // ★ MSA: Distributed lock for rider assignment
        // Redis 기반 분산 락 - 여러 인스턴스가 동시에 같은 라이더를 배정하지 못하도록 방지
        String lockKey = "lock:rider-assignment:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // tryLock(waitTime, leaseTime, unit)
            // waitTime: 락 획득까지 최대 대기 시간 (5초)
            // leaseTime: 락 자동 해제 시간 (3초) - 데드락 방지
            boolean acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Failed to acquire lock for rider assignment: orderId={}", orderId);
                return delivery;
            }

            // Find and assign nearest rider using Redis GEO
            // Redis GEO 자료구조로 현재 위치에서 가장 가까운 라이더를 찾음
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
            // 락 해제 - 반드시 현재 스레드가 보유한 락인지 확인 후 해제
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return delivery;
    }

    /**
     * 주문 ID로 배달 정보를 조회합니다.
     */
    public Delivery getDelivery(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }

    /**
     * 배달 상태를 업데이트합니다.
     * 라이더 앱에서 호출하여 배달 진행 상황을 갱신합니다.
     *
     * @param deliveryId 배달 ID
     * @param action 수행할 액션 (pickup, deliver, complete, cancel)
     */
    @Transactional
    public Delivery updateStatus(Long deliveryId, String action) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));

        // 각 액션에 따라 배달 엔티티의 상태 전이 메서드 호출
        switch (action) {
            case "pickup" -> delivery.pickUp();       // RIDER_ASSIGNED → PICKED_UP
            case "deliver" -> delivery.startDelivery(); // PICKED_UP → DELIVERING
            case "complete" -> delivery.complete();     // DELIVERING → DELIVERED
            case "cancel" -> delivery.cancel();         // → CANCELLED
            default -> throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return delivery;
    }
}
