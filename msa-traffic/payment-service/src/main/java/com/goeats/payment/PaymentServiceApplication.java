package com.goeats.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payment Service 메인 애플리케이션 클래스.
 *
 * <p>결제 마이크로서비스의 진입점으로, 주문 이벤트를 수신하여 결제를 처리하고
 * 결제 결과 이벤트를 발행하는 역할을 담당한다.</p>
 *
 * <h3>scanBasePackages 구성</h3>
 * <ul>
 *   <li>{@code com.goeats.payment} - 결제 서비스 자체 컴포넌트</li>
 *   <li>{@code com.goeats.common.exception} - 공통 예외 처리 (GlobalExceptionHandler 등)</li>
 *   <li>{@code com.goeats.common.outbox} - Transactional Outbox 패턴 모듈
 *       (OutboxService, OutboxRelay 등 - DB에 이벤트를 저장하고 스케줄러가 Kafka로 발행)</li>
 *   <li>{@code com.goeats.common.resilience} - Resilience4j 공통 설정 (Circuit Breaker, Rate Limiter 등)</li>
 * </ul>
 *
 * <h3>@EnableScheduling의 역할</h3>
 * <p>Outbox Relay가 @Scheduled 폴링 방식으로 outbox 테이블의 미발행 이벤트를 주기적으로 읽어
 * Kafka로 전송한다. 이 어노테이션이 없으면 Outbox 이벤트가 Kafka로 발행되지 않는다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 서비스가 직접 KafkaTemplate.send()로 이벤트를 발행했다.
 * Traffic에서는 Outbox 패턴을 사용하므로 common-outbox 모듈 스캔과 @EnableScheduling이 필수이다.
 * 이를 통해 "DB 저장 + 이벤트 발행"의 원자성을 보장한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 ApplicationEventPublisher로 같은 JVM 내 이벤트를 발행했다.
 * MSA에서는 서비스 간 통신이 네트워크를 거치므로, 이벤트 유실 방지를 위해
 * Outbox 패턴이라는 별도의 인프라 패턴이 필요하다.</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.goeats.payment",
        "com.goeats.common.exception",
        "com.goeats.common.outbox",
        "com.goeats.common.resilience"
})
@EnableScheduling  // ★ Required for Outbox Relay @Scheduled polling
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
