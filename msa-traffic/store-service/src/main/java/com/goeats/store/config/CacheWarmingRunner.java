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
 * Cache Warming Runner - 서비스 시작 시 인기 데이터를 Redis에 미리 로딩.
 *
 * <p>Cold Start 문제를 해결하기 위해 ApplicationContext가 초기화된 직후,
 * 트래픽을 받기 전에 영업중인 가게 데이터를 Redis 캐시에 미리 적재한다.</p>
 *
 * <h3>Cold Start 문제란?</h3>
 * <p>서비스가 재시작되면 Redis 캐시가 비어있는 상태(Cold)가 된다.
 * 이 상태에서 대량 트래픽이 들어오면 모든 요청이 DB를 직접 조회하여
 * DB 과부하 + 응답 지연(Latency Spike)이 발생한다.</p>
 *
 * <h3>해결: Cache Warming</h3>
 * <pre>
 * 1. ApplicationRunner.run() 실행 (서비스 시작 시 자동 호출)
 * 2. DB에서 영업중인(open=true) 가게 목록 조회
 * 3. 각 가게를 Redis에 저장 (TTL 30분)
 * 4. 메뉴 포함 상세 정보도 별도 캐시에 저장 (TTL 15분)
 * 5. 트래픽 수신 시작 → 캐시 히트율 높음 → DB 부하 최소화
 * </pre>
 *
 * <h3>캐시 키 구조</h3>
 * <ul>
 *   <li>{@code stores::{id}} - 가게 기본 정보 (TTL 30분)</li>
 *   <li>{@code stores:detail::{id}} - 가게 + 메뉴 상세 정보 (TTL 15분)</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시)를 사용하므로 Cache Warming이 필요 없다.
 * 로컬 캐시는 첫 요청 시 자동으로 채워지고, 단일 인스턴스이므로 Cold Start 영향이 작다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 Cache Warming 없이 서비스를 시작한다.
 * 배포/재시작 직후 대량 트래픽이 DB를 직접 조회하여 성능 저하가 발생한다.
 * Traffic 버전에서는 서비스 시작 시 자동으로 캐시를 적재하여 Cold Start를 방지한다.</p>
 *
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
    private final RedisTemplate<String, Object> redisTemplate;  // 수동 캐시 조작용 (Cache Warming에 사용)

    /**
     * 서비스 시작 시 자동 실행 - 영업중인 가게 데이터를 Redis에 미리 로딩.
     *
     * <p>ApplicationRunner 인터페이스 구현으로 Spring Boot가 자동 호출한다.
     * 영업중(open=true)인 가게만 로딩하여 Redis 메모리 사용을 최소화한다.</p>
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting cache warming...");

        // DB에서 영업중인 가게 목록 조회
        List<Store> openStores = storeRepository.findByOpenTrue();
        int count = 0;

        for (Store store : openStores) {
            // 가게 기본 정보 캐시 (TTL 30분)
            redisTemplate.opsForValue().set(
                    "stores::" + store.getId(), store, Duration.ofMinutes(30));

            // 가게 + 메뉴 상세 정보 캐시 (TTL 15분, 메뉴 변경이 더 빈번하므로 짧은 TTL)
            Store storeWithMenus = storeRepository.findWithMenusById(store.getId())
                    .orElse(store);
            redisTemplate.opsForValue().set(
                    "stores:detail::" + store.getId(), storeWithMenus, Duration.ofMinutes(15));

            count++;
        }

        log.info("Cache warming completed: {} stores pre-loaded", count);
    }
}
