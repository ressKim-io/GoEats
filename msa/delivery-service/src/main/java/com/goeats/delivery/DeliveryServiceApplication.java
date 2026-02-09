package com.goeats.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 배달 서비스(Delivery Service)의 Spring Boot 애플리케이션 진입점.
 *
 * <p>MSA에서 배달 서비스는 독립적인 프로세스로 실행되며,
 * Redis(Redisson)를 사용하여 분산 락 기반의 라이더 매칭을 처리합니다.
 * 배달 생성은 결제 완료 이벤트(PaymentCompletedEvent)를 Kafka로 수신하여 트리거됩니다.</p>
 *
 * <p>사용하는 인프라:
 * <ul>
 *   <li>Kafka: 결제 완료 이벤트 수신 (PaymentEventListener)</li>
 *   <li>Redis: 라이더 위치 관리 (GEO 자료구조), 분산 락 (Redisson)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 하나의 애플리케이션에서 DB 비관적 락(@Lock)으로 동시성 제어
 * - MSA: Redis 기반 분산 락(Redisson)으로 여러 인스턴스 간 동시성 제어
 * - MSA: Redis GEO로 실시간 라이더 위치 기반 매칭 가능</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.goeats.delivery", "com.goeats.common.exception"})
public class DeliveryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }
}
