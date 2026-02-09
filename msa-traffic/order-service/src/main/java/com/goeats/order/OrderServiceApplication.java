package com.goeats.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GoEats Order Service - 주문 마이크로서비스 진입점
 *
 * <h3>역할</h3>
 * 주문 생성, 조회, 취소를 담당하는 핵심 서비스.
 * Saga 오케스트레이터로서 주문 → 결제 → 배달 흐름을 조율한다.
 *
 * <h3>scanBasePackages 설명</h3>
 * <ul>
 *   <li>com.goeats.order - Order 서비스 자체 패키지</li>
 *   <li>com.goeats.common.exception - 공통 예외 처리 (GlobalExceptionHandler)</li>
 *   <li>com.goeats.common.outbox - Transactional Outbox 패턴 모듈</li>
 *   <li>com.goeats.common.resilience - Resilience4j 공통 설정</li>
 * </ul>
 *
 * <h3>활성화된 기능</h3>
 * <ul>
 *   <li>@EnableFeignClients - OpenFeign으로 Store 서비스와 HTTP 통신</li>
 *   <li>@EnableScheduling - Outbox Relay의 @Scheduled 폴링에 필요</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서는 @EnableScheduling이 없었다 (Outbox 패턴 미사용, Kafka 직접 발행).
 * MSA-Traffic에서는 Outbox Relay가 주기적으로 outbox 테이블을 폴링하여
 * Kafka로 이벤트를 발행하므로 @EnableScheduling이 필수다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 하나의 Application 클래스에 모든 도메인이 포함되었다.
 * MSA에서는 도메인별로 독립 서비스를 가지며, 공통 모듈은 scanBasePackages로 가져온다.
 *
 * <h3>포트</h3>
 * Order Service: 8081
 */
@SpringBootApplication(scanBasePackages = {
        "com.goeats.order",
        "com.goeats.common.exception",
        "com.goeats.common.outbox",
        "com.goeats.common.resilience"
})
@EnableFeignClients
@EnableScheduling  // ★ Required for Outbox Relay @Scheduled polling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
