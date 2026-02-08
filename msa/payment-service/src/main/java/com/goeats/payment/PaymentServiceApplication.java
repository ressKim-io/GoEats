package com.goeats.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 결제 서비스(Payment Service)의 Spring Boot 애플리케이션 진입점.
 *
 * <p>MSA에서 결제 서비스는 독립적인 프로세스로 실행되며,
 * Kafka Consumer로 주문 이벤트(OrderCreatedEvent)를 수신하여 결제를 처리합니다.
 * 결제 생성은 REST API가 아닌 이벤트 기반으로 트리거됩니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 하나의 애플리케이션 안에서 OrderService가 PaymentService를 직접 호출
 * - MSA: 결제 서비스가 별도 프로세스로 실행, Kafka 이벤트로 느슨하게 연결
 * - MSA: 결제 서비스만 독립적으로 스케일 아웃(인스턴스 증가) 가능</p>
 *
 * <p>@ComponentScan으로 common 모듈의 예외 처리도 함께 스캔합니다.
 * MSA에서는 공통 모듈(common)을 별도 라이브러리로 분리하여 각 서비스가 의존합니다.</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.goeats.payment", "com.goeats.common.exception"})
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
