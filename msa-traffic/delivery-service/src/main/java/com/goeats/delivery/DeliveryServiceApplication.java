package com.goeats.delivery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배달 서비스(Delivery Service) - MSA Traffic 버전 메인 애플리케이션.
 *
 * <p>배달 생성, 라이더 매칭, 배달 상태 관리를 담당하는 마이크로서비스이다.
 * 결제 완료 이벤트(PaymentCompletedEvent)를 Kafka로 수신하여 배달을 자동 생성한다.</p>
 *
 * <h3>scanBasePackages 구성</h3>
 * <ul>
 *   <li>{@code com.goeats.delivery} - 배달 서비스 자체 패키지</li>
 *   <li>{@code com.goeats.common.exception} - 공통 예외 처리 (GlobalExceptionHandler)</li>
 *   <li>{@code com.goeats.common.outbox} - Transactional Outbox 패턴 공통 모듈</li>
 *   <li>{@code com.goeats.common.resilience} - Resilience4j 공통 설정</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 모든 도메인이 하나의 애플리케이션에 포함되어 단일 @SpringBootApplication으로 동작한다.
 * MSA에서는 배달 서비스만 독립 프로세스로 실행되며, 다른 서비스와 Kafka/HTTP로 통신한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA는 단순히 서비스를 분리만 했지만, Traffic 버전은 Outbox 패턴, ShedLock 등
 * 프로덕션급 패턴을 공통 모듈로 분리하여 scan한다.</p>
 *
 * <p>{@code @EnableScheduling}: Outbox Relay의 @Scheduled 폴링을 활성화한다.
 * ShedLock과 함께 사용하여 다중 인스턴스 환경에서 중복 실행을 방지한다.</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.goeats.delivery",
        "com.goeats.common.exception",
        "com.goeats.common.outbox",
        "com.goeats.common.resilience"
})
@EnableScheduling  // Outbox Relay @Scheduled 스케줄러 활성화
public class DeliveryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeliveryServiceApplication.class, args);
    }
}
