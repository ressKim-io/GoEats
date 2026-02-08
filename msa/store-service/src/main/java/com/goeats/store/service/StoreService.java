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

    public Store getStoreWithMenus(Long id) {
        return storeRepository.findWithMenusById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }
}
