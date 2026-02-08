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
@Transactional(readOnly = true)
public class StoreService {

    private final StoreRepository storeRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Cacheable(value = "stores", key = "#id")
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreFallback")
    public Store getStore(Long id) {
        return storeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    @Cacheable(value = "stores:detail", key = "#id")
    @CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreWithMenusFallback")
    public Store getStoreWithMenus(Long id) {
        return storeRepository.findWithMenusById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    @CircuitBreaker(name = "storeDb", fallbackMethod = "getOpenStoresFallback")
    public List<Store> getOpenStores() {
        return storeRepository.findByOpenTrue();
    }

    // ★ Multi-level Fallback: try Redis manual cache → throw
    @SuppressWarnings("unused")
    private Store getStoreFallback(Long id, Throwable t) {
        log.warn("Store DB circuit breaker fallback for id={}: {}", id, t.getMessage());
        Object cached = redisTemplate.opsForValue().get("stores::" + id);
        if (cached instanceof Store store) {
            log.info("Returning cached store for id={}", id);
            return store;
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service temporarily unavailable");
    }

    @SuppressWarnings("unused")
    private Store getStoreWithMenusFallback(Long id, Throwable t) {
        log.warn("Store DB circuit breaker fallback for detail id={}: {}", id, t.getMessage());
        Object cached = redisTemplate.opsForValue().get("stores:detail::" + id);
        if (cached instanceof Store store) {
            log.info("Returning cached store detail for id={}", id);
            return store;
        }
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service temporarily unavailable");
    }

    @SuppressWarnings("unused")
    private List<Store> getOpenStoresFallback(Throwable t) {
        log.warn("Open stores circuit breaker fallback: {}", t.getMessage());
        return List.of(); // Return empty list as graceful degradation
    }
}
