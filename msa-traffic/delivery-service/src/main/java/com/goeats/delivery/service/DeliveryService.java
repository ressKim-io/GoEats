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
 * 배달 서비스(DeliveryService) - 배달 생성, 라이더 매칭, 상태 관리의 핵심 비즈니스 로직.
 *
 * <p>이 클래스는 MSA-Traffic의 가장 복잡한 동시성 제어 패턴을 구현한다:
 * Fencing Token + Redisson 분산 락 + Bulkhead 격리.</p>
 *
 * <h3>적용된 패턴 3가지</h3>
 *
 * <h4>1. Fencing Token (핵심 패턴)</h4>
 * <p>Redis AtomicLong으로 단조 증가 토큰을 발급하고, DB UPDATE 시 토큰을 검증한다.
 * 분산 락이 만료된 후에도 stale 스레드가 DB를 덮어쓰는 것을 방지한다.</p>
 * <pre>
 * Thread A: 락 획득, 토큰=5 → GC Pause로 락 만료
 * Thread B: 락 획득, 토큰=6 → DB UPDATE (lastFencingToken=6) 성공
 * Thread A: GC 복귀 → 토큰=5로 DB UPDATE 시도 → 5 < 6 이므로 거부!
 * </pre>
 *
 * <h4>2. Redisson 분산 락</h4>
 * <p>동일 주문의 라이더 매칭이 동시에 실행되지 않도록 Redis 기반 분산 락을 사용한다.
 * tryLock(5초 대기, 3초 자동 해제)으로 데드락을 방지한다.</p>
 *
 * <h4>3. @Bulkhead (스레드 격리)</h4>
 * <p>Resilience4j Bulkhead로 동시 실행 스레드 수를 제한한다.
 * 라이더 매칭에 트래픽이 폭주해도 다른 API(배달 조회 등)에 영향을 주지 않도록 격리한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 DB 비관적 락(SELECT FOR UPDATE)과 @Transactional로 동시성을 제어한다.
 * 단일 DB이므로 분산 락이나 Fencing Token이 필요 없다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 Redisson 분산 락만 사용한다.
 * 문제: 락 TTL 만료 후 이전 스레드가 DB를 업데이트하면 데이터 정합성이 깨진다.
 * Traffic 버전에서는 Fencing Token + Bulkhead를 추가하여 안전성과 안정성을 강화한다.</p>
 *
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
@Transactional(readOnly = true)  // 기본 읽기 전용 트랜잭션 (성능 최적화)
public class DeliveryService {

    private final DeliveryRepository deliveryRepository;
    private final RiderMatchingService riderMatchingService;  // Redis GEO 기반 라이더 매칭
    private final RedissonClient redissonClient;  // Redis 분산 락 + Fencing Token 카운터

    /**
     * 배달 생성 + 라이더 자동 매칭.
     *
     * <p>결제 완료 이벤트 수신 시 호출된다. 배달 엔티티를 생성한 후
     * Fencing Token + 분산 락을 사용하여 안전하게 라이더를 매칭한다.</p>
     *
     * <h4>실행 흐름</h4>
     * <ol>
     *   <li>배달 엔티티 생성 및 저장 (상태: WAITING)</li>
     *   <li>Redis AtomicLong으로 Fencing Token 발급 (단조 증가)</li>
     *   <li>Redisson 분산 락 획득 시도 (5초 대기, 3초 TTL)</li>
     *   <li>Redis GEO로 가장 가까운 라이더 검색</li>
     *   <li>Fencing Token과 함께 DB UPDATE (토큰 검증 포함)</li>
     *   <li>분산 락 해제</li>
     * </ol>
     *
     * @param orderId 주문 ID
     * @param deliveryAddress 배달 주소
     * @return 생성된 배달 엔티티
     */
    @Transactional
    @Bulkhead(name = "riderAssignment")  // 라이더 매칭 동시 실행 수 제한 (스레드 풀 고갈 방지)
    public Delivery createDelivery(Long orderId, String deliveryAddress) {
        // 1단계: 배달 엔티티 생성 (상태: WAITING)
        Delivery delivery = Delivery.builder()
                .orderId(orderId)
                .deliveryAddress(deliveryAddress)
                .build();
        delivery = deliveryRepository.save(delivery);

        // ★ 2단계: Fencing Token 발급 - Redis AtomicLong으로 단조 증가 토큰 생성
        // 주문별 독립 카운터: "fencing:rider:{orderId}"
        RAtomicLong fencingCounter = redissonClient.getAtomicLong("fencing:rider:" + orderId);
        long fencingToken = fencingCounter.incrementAndGet();  // 원자적 증가 (1, 2, 3, ...)

        // 3단계: Redisson 분산 락 획득 (동일 주문에 대한 동시 라이더 매칭 방지)
        String lockKey = "lock:rider-assignment:" + orderId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // tryLock(대기시간 5초, 자동해제 3초) - 데드락 방지를 위해 반드시 TTL 설정
            boolean acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Failed to acquire lock for rider assignment: orderId={}", orderId);
                return delivery;  // 락 획득 실패 시 배달은 생성되었지만 라이더 미배정 상태로 반환
            }

            // 4단계: Redis GEO 기반 가장 가까운 라이더 검색 (반경 5km)
            String riderId = riderMatchingService.findNearestRider(
                    127.0276, 37.4979, 5.0);  // 기본 좌표 (서울 강남 기준)

            if (riderId != null) {
                // ★ 5단계: Fencing Token과 함께 DB UPDATE
                // DB에서 "lastFencingToken < 새 토큰"일 때만 UPDATE 성공
                int updated = deliveryRepository.updateWithFencingToken(
                        delivery.getId(),
                        "Rider-" + riderId,
                        "010-" + riderId,
                        fencingToken);

                if (updated == 0) {
                    // 토큰이 stale한 경우: 더 새로운 토큰이 이미 기록되어 있음
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
            Thread.currentThread().interrupt();  // 인터럽트 상태 복원
            log.error("Interrupted during rider assignment", e);
        } finally {
            // 6단계: 분산 락 해제 (현재 스레드가 보유한 경우에만)
            if (lock.isLocked() && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        // 최신 상태의 배달 엔티티 반환 (라이더 배정 정보가 반영된 상태)
        return deliveryRepository.findById(delivery.getId()).orElse(delivery);
    }

    /**
     * 배달 상태 업데이트 - 라이더 앱에서 호출.
     *
     * <p>@Bulkhead로 동시 상태 변경 수를 제한하여 DB 과부하를 방지한다.</p>
     *
     * @param deliveryId 배달 ID
     * @param status 변경할 상태
     * @return 업데이트된 배달 엔티티
     */
    @Transactional
    @Bulkhead(name = "deliveryStatusUpdate")  // 상태 업데이트 동시 실행 수 제한
    public Delivery updateDeliveryStatus(Long deliveryId, DeliveryStatus status) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
        delivery.updateStatus(status);
        return delivery;  // JPA 더티 체킹으로 자동 UPDATE
    }

    /** 배달 ID로 조회 */
    public Delivery getDelivery(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }

    /** 주문 ID로 배달 조회 (주문 서비스에서 배달 상태 확인 시 사용) */
    public Delivery getDeliveryByOrderId(Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DELIVERY_NOT_FOUND));
    }
}
