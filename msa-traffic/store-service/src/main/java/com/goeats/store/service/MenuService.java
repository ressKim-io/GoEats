package com.goeats.store.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.store.entity.Menu;
import com.goeats.store.repository.MenuRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 메뉴 서비스(MenuService) - 메뉴 조회 + Multi-level Cache Fallback.
 *
 * <p>메뉴 조회 시 Redis 캐시 → DB → CircuitBreaker Fallback 순서로
 * 다단계 캐시 전략을 적용하여 높은 가용성과 성능을 보장한다.</p>
 *
 * <h3>Multi-level Cache Fallback 흐름</h3>
 * <pre>
 * Level 1: @Cacheable → Redis 캐시 히트 시 바로 반환 (가장 빠름)
 * Level 2: Redis 캐시 미스 → DB 조회 후 Redis에 자동 캐싱
 * Level 3: DB 장애 시 @CircuitBreaker → fallback 메서드 실행
 *          → RedisTemplate으로 수동 Redis 캐시 조회 시도
 *          → 캐시에도 없으면 SERVICE_UNAVAILABLE 예외
 * </pre>
 *
 * <h3>왜 Fallback에서 다시 Redis를 조회하나?</h3>
 * <p>@Cacheable과 fallback은 다른 경로로 동작한다.
 * @Cacheable이 캐시 히트하면 DB를 아예 호출하지 않으므로 @CircuitBreaker도 동작하지 않는다.
 * 하지만 @Cacheable이 캐시 미스 + DB 호출에서 실패하면 @CircuitBreaker가 동작하고,
 * 이때 fallback에서 RedisTemplate으로 직접 캐시를 조회하면 이전에 캐싱된 데이터가 있을 수 있다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시) + 단일 DB로 충분하다.
 * DB 장애 시 전체 서비스가 중단되므로 Fallback이 의미 없다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 @Cacheable + DB 조회만 있고, DB 장애 시 예외를 던진다.
 * Traffic 버전에서는 @CircuitBreaker + Fallback으로 DB 장애 시에도
 * 캐시된 데이터를 반환하여 서비스 가용성을 유지한다 (Graceful Degradation).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // 읽기 전용 트랜잭션 (성능 최적화)
public class MenuService {

    private final MenuRepository menuRepository;
    private final RedisTemplate<String, Object> redisTemplate;  // Fallback용 수동 Redis 조회

    /**
     * 개별 메뉴 조회 - Redis 캐시 → DB → Fallback.
     *
     * <p>@Cacheable: "menus" 캐시에 menuId를 키로 저장 (TTL 10분)</p>
     * <p>@CircuitBreaker: DB 연속 실패 시 회로 차단 → fallback 실행</p>
     */
    @Cacheable(value = "menus", key = "#menuId")  // Redis 캐시 (키: menus::{menuId}, TTL: 10분)
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getMenuFallback")  // DB 장애 시 fallback
    public Menu getMenu(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }

    /**
     * 가게별 메뉴 목록 조회 - Redis 캐시 → DB → Fallback.
     *
     * <p>@Cacheable: "menus:store" 캐시에 storeId를 키로 저장 (TTL 10분)</p>
     * <p>판매 가능한(available=true) 메뉴만 반환한다.</p>
     */
    @Cacheable(value = "menus:store", key = "#storeId")  // Redis 캐시 (키: menus:store::{storeId})
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getMenusByStoreFallback")
    public List<Menu> getMenusByStore(Long storeId) {
        return menuRepository.findByStoreIdAndAvailableTrue(storeId);
    }

    /**
     * ★ 개별 메뉴 Fallback - DB 장애 시 Redis 수동 캐시 조회.
     *
     * <p>@CircuitBreaker가 회로 차단 상태일 때 호출된다.
     * RedisTemplate으로 직접 캐시를 조회하여 이전에 캐싱된 데이터를 반환한다.</p>
     */
    // ★ Fallback: try manual Redis cache → throw
    @SuppressWarnings("unused")
    private Menu getMenuFallback(Long menuId, Throwable t) {
        log.warn("Menu DB fallback for menuId={}: {}", menuId, t.getMessage());
        // RedisTemplate으로 수동 캐시 조회 (키 형식: "menus::{menuId}")
        Object cached = redisTemplate.opsForValue().get("menus::" + menuId);
        if (cached instanceof Menu menu) {
            return menu;  // 캐시에 있으면 반환 (Graceful Degradation)
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);  // 캐시에도 없으면 에러
    }

    /**
     * ★ 가게별 메뉴 목록 Fallback - DB 장애 시 빈 목록 반환.
     *
     * <p>메뉴 목록은 빈 리스트로 반환하여 사용자에게 "메뉴 없음"으로 표시한다.
     * 완전한 서비스 중단보다 나은 사용자 경험을 제공한다 (Graceful Degradation).</p>
     */
    @SuppressWarnings("unused")
    private List<Menu> getMenusByStoreFallback(Long storeId, Throwable t) {
        log.warn("Menu list fallback for storeId={}: {}", storeId, t.getMessage());
        return List.of();  // 빈 목록 반환 (Graceful Degradation)
    }
}
