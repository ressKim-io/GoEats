package com.goeats.store.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.store.entity.Store;
import com.goeats.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가게 비즈니스 로직을 담당하는 서비스.
 *
 * <p>@Cacheable로 Redis 분산 캐시를 적용하여 가게 조회 성능을 최적화합니다.
 * 가게 정보는 변경 빈도가 낮아 캐시 효과가 매우 큽니다.</p>
 *
 * <p>두 가지 조회 메서드:
 * <ul>
 *   <li>getStore(): 가게 기본 정보만 조회 (Redis 캐시 적용)</li>
 *   <li>getStoreWithMenus(): 가게 + 메뉴를 @EntityGraph로 함께 조회 (N+1 방지)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: Caffeine 로컬 캐시 → 각 JVM마다 별도 캐시, 불일치 가능
 * - MSA: Redis 분산 캐시 → 모든 인스턴스가 동일한 캐시 데이터 공유
 *   → 가게 정보 변경 시 한 번의 캐시 무효화로 모든 인스턴스에 반영</p>
 */

/**
 * ★ MSA: @Cacheable with Redis distributed cache.
 * Cache is shared across all store-service instances.
 *
 * Compare with Monolithic: @Cacheable with Caffeine (local cache).
 * Each JVM has its own cache, no sharing between instances.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;

    /**
     * 가게 기본 정보를 조회합니다 (Redis 캐시 적용).
     *
     * 캐시 키: stores::{id} (예: stores::1)
     * 첫 조회 시 DB에서 읽어 Redis에 저장, 이후 Redis에서 직접 반환합니다.
     * 모든 store-service 인스턴스가 같은 Redis 캐시를 공유합니다.
     */

    /**
     * ★ MSA: Redis cache - shared across multiple service instances.
     * When store info is updated, all instances see the new value.
     *
     * Compare with Monolithic: Caffeine local cache - only one JVM.
     */
    @Cacheable(value = "stores", key = "#id")
    public Store getStore(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    /**
     * 가게 정보를 메뉴 목록과 함께 조회합니다.
     * @EntityGraph로 LEFT JOIN FETCH하여 N+1 문제를 방지합니다.
     */
    public Store getStoreWithMenus(Long id) {
        return storeRepository.findWithMenusById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }
}
