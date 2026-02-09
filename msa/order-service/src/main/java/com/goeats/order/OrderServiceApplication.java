package com.goeats.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;

/**
 * 주문 서비스(Order Service)의 진입점 (Entry Point).
 *
 * <p>MSA에서는 각 서비스가 독립적인 Spring Boot 애플리케이션으로 실행됩니다.
 * 이 클래스는 주문 서비스만의 독립적인 main() 메서드를 가집니다.</p>
 *
 * <p>핵심 어노테이션 설명:</p>
 * <ul>
 *   <li>{@code @EnableFeignClients} - OpenFeign을 활성화하여 다른 MSA 서비스를
 *       HTTP로 호출할 수 있게 합니다. 인터페이스만 정의하면 Spring Cloud가
 *       런타임에 HTTP 클라이언트 구현체를 자동 생성합니다.</li>
 *   <li>{@code @ComponentScan} - common-exception 모듈의 GlobalExceptionHandler를
 *       스캔 대상에 포함시킵니다. MSA에서는 공통 모듈을 별도 라이브러리로 분리하므로
 *       명시적으로 패키지를 지정해야 합니다.</li>
 * </ul>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 하나의 Application 클래스만 존재하고,
 * 모든 도메인(주문, 결제, 배달, 가게)이 같은 JVM에서 실행됩니다.
 * MSA에서는 서비스마다 별도의 Application 클래스가 있고, 각각 독립적인 프로세스로 실행됩니다.</p>
 */
@SpringBootApplication
@EnableFeignClients
@ComponentScan(basePackages = {"com.goeats.order", "com.goeats.common.exception"})
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
