package com.goeats.store.config;

import com.goeats.store.entity.Store;
import com.goeats.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * ★ Cache Warming Runner
 *
 * Problem: Cold start → first requests hit DB, causing latency spike
 * Solution: Pre-load popular stores/menus into Redis at startup
 *
 * Runs after ApplicationContext is ready, before receiving traffic.
 * Only loads open (active) stores to minimize memory usage.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmingRunner implements ApplicationRunner {

    private final StoreRepository storeRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting cache warming...");

        List<Store> openStores = storeRepository.findByOpenTrue();
        int count = 0;

        for (Store store : openStores) {
            redisTemplate.opsForValue().set(
                    "stores::" + store.getId(), store, Duration.ofMinutes(30));

            Store storeWithMenus = storeRepository.findWithMenusById(store.getId())
                    .orElse(store);
            redisTemplate.opsForValue().set(
                    "stores:detail::" + store.getId(), storeWithMenus, Duration.ofMinutes(15));

            count++;
        }

        log.info("Cache warming completed: {} stores pre-loaded", count);
    }
}
