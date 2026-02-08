package com.goeats.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 가게 서비스(Store Service) - MSA Traffic 버전 메인 애플리케이션.
 *
 * <p>가게(Store)와 메뉴(Menu) 정보의 조회를 담당하는 마이크로서비스이다.
 * 읽기 트래픽이 가장 많은 서비스로, 캐싱 전략이 핵심이다.</p>
 *
 * <h3>scanBasePackages 구성</h3>
 * <ul>
 *   <li>{@code com.goeats.store} - 가게 서비스 자체 패키지</li>
 *   <li>{@code com.goeats.common.exception} - 공통 예외 처리 (GlobalExceptionHandler)</li>
 *   <li>{@code com.goeats.common.resilience} - Resilience4j 공통 설정</li>
 * </ul>
 *
 * <h3>★ @EnableCaching</h3>
 * <p>Spring Cache 추상화를 활성화하여 @Cacheable, @CacheEvict 등의 어노테이션이 동작하도록 한다.
 * 실제 캐시 구현체는 {@link com.goeats.store.config.RedisConfig}에서 Redis로 설정한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시)를 사용한다. 단일 인스턴스이므로 로컬 캐시로 충분하다.
 * MSA에서는 여러 인스턴스가 동일 데이터를 캐싱해야 하므로 Redis(분산 캐시)가 필수적이다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA도 Redis 캐시를 사용하지만, Cache Warming이나 Multi-level Fallback이 없다.
 * Traffic 버전에서는 CacheWarmingRunner로 Cold Start를 방지하고,
 * CircuitBreaker Fallback으로 DB 장애 시에도 캐시된 데이터를 반환한다.</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.goeats.store",
        "com.goeats.common.exception",
        "com.goeats.common.resilience"
})
@EnableCaching  // Spring Cache 추상화 활성화 (Redis 기반 @Cacheable 사용)
public class StoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
