package com.goeats.store.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.store.entity.Store;
import com.goeats.store.repository.StoreRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 가게 서비스(StoreService) - 가게 조회 + Multi-level Cache Fallback (핵심 서비스).
 *
 * <p>가게 조회 시 3단계 캐시 전략을 적용하여 높은 가용성과 성능을 보장한다.
 * 읽기 트래픽이 가장 많은 서비스이므로 캐싱 최적화가 매우 중요하다.</p>
 *
 * <h3>Multi-level Cache Fallback 3단계</h3>
 * <pre>
 * Level 1: @Cacheable → Redis 캐시 히트 시 바로 반환 (가장 빠름, ~1ms)
 * Level 2: Redis 캐시 미스 → DB 조회 후 Redis에 자동 캐싱 (~10ms)
 * Level 3: DB 장애 시 @CircuitBreaker → fallback 메서드 실행
 *          → RedisTemplate으로 수동 Redis 캐시 조회 시도
 *          → 캐시에도 없으면 SERVICE_UNAVAILABLE 또는 빈 목록 반환
 * </pre>
 *
 * <h3>Cache Warming과의 연계</h3>
 * <p>CacheWarmingRunner가 서비스 시작 시 영업중 가게를 Redis에 미리 로딩하므로,
 * 첫 번째 요청부터 Level 1(Redis 캐시)에서 바로 응답할 수 있다.
 * Cache Warming 없이는 서비스 재시작 후 모든 요청이 Level 2(DB)를 거쳐야 한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시) + 단일 DB로 구성된다.
 * 로컬 캐시는 네트워크 오버헤드가 없어 빠르지만, 인스턴스 간 캐시 불일치 문제가 있다.
 * MSA에서는 Redis(분산 캐시)로 모든 인스턴스가 동일한 캐시를 공유한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 @Cacheable만 사용하고 DB 장애 시 예외를 던진다.
 * Traffic 버전에서는 @CircuitBreaker + Fallback으로 DB 장애 시에도
 * 캐시된 데이터를 반환하여 서비스 가용성을 유지한다 (Graceful Degradation).</p>
 *
 * ★ Traffic MSA: Multi-level Cache Fallback
 *
 * Level 1: @Cacheable → Redis cache (fast, distributed)
 * Level 2: DB query (slower but reliable)
 * Level 3: Circuit Breaker fallback (cached or default value)
 *
 * vs Basic MSA: Simple @Cacheable + throw exception on miss
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // 읽기 전용 트랜잭션 (JPA 더티 체킹 비활성화로 성능 최적화)
public class StoreService {

    private final StoreRepository storeRepository;
    private final RedisTemplate<String, Object> redisTemplate;  // Fallback용 수동 Redis 조회

    /**
     * 가게 기본 정보 조회 - Redis 캐시 → DB → Fallback.
     *
     * <p>@Cacheable: "stores" 캐시에 id를 키로 저장 (TTL 30분)</p>
     * <p>@CircuitBreaker: DB 연속 실패 시 회로 차단 → getStoreFallback 실행</p>
     */
    @Cacheable(value = "stores", key = "#id")  // Redis 캐시 (키: stores::{id}, TTL: 30분)
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreFallback")  // DB 장애 시 fallback
    public Store getStore(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    /**
     * 가게 상세 정보 조회 (메뉴 포함) - Redis 캐시 → DB Fetch Join → Fallback.
     *
     * <p>@Cacheable: "stores:detail" 캐시에 id를 키로 저장 (TTL 15분, 메뉴 변경이 빈번하므로 짧은 TTL)</p>
     * <p>Fetch Join으로 가게 + 메뉴를 한 번의 쿼리로 조회한다 (N+1 문제 방지).</p>
     */
    @Cacheable(value = "stores:detail", key = "#id")  // Redis 캐시 (키: stores:detail::{id}, TTL: 15분)
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreWithMenusFallback")
    public Store getStoreWithMenus(Long id) {
        return storeRepository.findWithMenusById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    /**
     * 영업중인 가게 목록 조회 - DB 직접 조회 + CircuitBreaker.
     *
     * <p>목록 조회는 @Cacheable 없이 DB에서 직접 조회한다.
     * (목록 캐싱은 무효화가 복잡하므로 개별 항목만 캐싱)</p>
     * <p>DB 장애 시 빈 목록을 반환하여 서비스 중단을 방지한다.</p>
     */
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getOpenStoresFallback")
    public List<Store> getOpenStores() {
        return storeRepository.findByOpenTrue();
    }

    /**
     * ★ 가게 기본 정보 Fallback - DB 장애 시 Redis 수동 캐시 조회.
     *
     * <p>@CircuitBreaker 회로 차단 시 호출된다.
     * RedisTemplate으로 "stores::{id}" 키를 직접 조회하여
     * 이전에 캐싱된 가게 정보를 반환한다.</p>
     */
    // ★ Multi-level Fallback: try Redis manual cache → throw
    @SuppressWarnings("unused")
    private Store getStoreFallback(Long id, Throwable t) {
        log.warn("Store DB circuit breaker fallback for id={}: {}", id, t.getMessage());
        // RedisTemplate으로 수동 캐시 조회 (키 형식: "stores::{id}")
        Object cached = redisTemplate.opsForValue().get("stores::" + id);
        if (cached instanceof Store store) {
            log.info("Returning cached store for id={}", id);
            return store;  // 캐시에 있으면 반환 (Graceful Degradation)
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service temporarily unavailable");
    }

    /**
     * ★ 가게 상세 정보 Fallback - DB 장애 시 Redis 수동 캐시 조회.
     *
     * <p>메뉴 포함 상세 정보를 "stores:detail::{id}" 키에서 조회한다.
     * Cache Warming으로 미리 로딩된 데이터가 있을 수 있다.</p>
     */
    @SuppressWarnings("unused")
    private Store getStoreWithMenusFallback(Long id, Throwable t) {
        log.warn("Store DB circuit breaker fallback for detail id={}: {}", id, t.getMessage());
        // Cache Warming으로 미리 로딩된 상세 정보 캐시 조회
        Object cached = redisTemplate.opsForValue().get("stores:detail::" + id);
        if (cached instanceof Store store) {
            log.info("Returning cached store detail for id={}", id);
            return store;
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service temporarily unavailable");
    }

    /**
     * ★ 영업중 가게 목록 Fallback - DB 장애 시 빈 목록 반환.
     *
     * <p>가게 목록은 캐싱하지 않으므로 Redis 조회 없이 빈 목록을 반환한다.
     * 사용자에게 "현재 영업중인 가게가 없습니다"로 표시된다 (Graceful Degradation).</p>
     */
    @SuppressWarnings("unused")
    private List<Store> getOpenStoresFallback(Throwable t) {
        log.warn("Open stores circuit breaker fallback: {}", t.getMessage());
        return List.of(); // Return empty list as graceful degradation
        // 빈 목록 반환: 완전한 서비스 중단보다 나은 사용자 경험 제공
    }
}
