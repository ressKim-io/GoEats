package com.goeats.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;

/**
 * 가게 서비스(Store Service)의 Spring Boot 애플리케이션 진입점.
 *
 * <p>@EnableCaching으로 Redis 기반 분산 캐시를 활성화합니다.
 * 가게/메뉴 정보는 자주 조회되지만 변경은 드물기 때문에 캐시 효과가 큽니다.
 * 다른 서비스(예: order-service)가 OpenFeign으로 이 서비스의 API를 호출합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: @EnableCaching + Caffeine(로컬 캐시) → 단일 JVM 내 캐시
 *   → 서버가 여러 대면 각 서버마다 다른 캐시 데이터 보유 가능 (캐시 불일치)
 * - MSA: @EnableCaching + Redis(분산 캐시) → 모든 인스턴스가 같은 캐시 공유
 *   → 가게 정보가 변경되면 모든 인스턴스에서 즉시 반영됨
 *   → store-service 인스턴스가 3개여도 캐시 일관성 보장</p>
 */
@SpringBootApplication
@EnableCaching  // Redis 분산 캐시 활성화 (application.yml에서 Redis 설정)
@ComponentScan(basePackages = {"com.goeats.store", "com.goeats.common.exception"})
public class StoreServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(StoreServiceApplication.class, args);
    }
}
