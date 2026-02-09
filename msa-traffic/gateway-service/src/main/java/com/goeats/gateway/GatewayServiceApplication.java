package com.goeats.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * GoEats Gateway Service - API Gateway 진입점 (Spring Cloud Gateway)
 *
 * <h3>역할</h3>
 * 모든 클라이언트 요청의 단일 진입점(Single Entry Point)으로,
 * 라우팅, 인증, Rate Limiting, Circuit Breaker 등 횡단 관심사(Cross-Cutting Concerns)를 처리한다.
 *
 * <h3>MSA-Traffic에서 사용하는 고급 패턴</h3>
 * <ul>
 *   <li>Redis 기반 분산 Rate Limiting (Token Bucket Algorithm)</li>
 *   <li>JWT 인증 필터 (GlobalFilter) - 다운스트림 서비스에 X-User-Id 전파</li>
 *   <li>Circuit Breaker + Fallback - 하위 서비스 장애 시 우아한 응답</li>
 *   <li>서비스별 라우팅 및 부하 분산</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 Gateway가 없어 클라이언트가 각 서비스에 직접 접근했다.
 * MSA-Traffic에서는 Gateway를 통해 인증/인가, 트래픽 제어, 장애 격리를 중앙에서 관리한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 서블릿 필터(Filter)로 인증을 처리했지만, MSA에서는 서비스가 분산되어 있으므로
 * Gateway에서 JWT를 검증하고, 내부 네트워크에서는 X-User-Id 헤더를 신뢰하는 구조를 사용한다.
 *
 * <h3>포트</h3>
 * Gateway: 8080 (모든 외부 요청은 여기로 진입)
 */
@SpringBootApplication
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
