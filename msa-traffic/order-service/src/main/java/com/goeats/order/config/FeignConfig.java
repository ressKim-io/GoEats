package com.goeats.order.config;

import feign.Request;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Feign 클라이언트 타임아웃 설정
 *
 * <h3>역할</h3>
 * 서비스 간 HTTP 통신(OpenFeign)의 연결/읽기 타임아웃을 설정한다.
 * 타임아웃 미설정 시 무한 대기로 스레드가 고갈될 수 있다.
 *
 * <h3>계단식 타임아웃 계층 (Cascading Timeout Hierarchy)</h3>
 * <pre>
 * Gateway:      8s  (가장 바깥, 클라이언트 응답 타임아웃)
 *   └─ Service: 5s  (Resilience4j TimeLimiter)
 *       └─ Feign:   3s connect + 5s read (가장 안쪽, 실제 HTTP 호출)
 * </pre>
 *
 * <h3>왜 계단식으로 설정하는가?</h3>
 * <ul>
 *   <li>Feign 타임아웃 ≤ Service TimeLimiter → Feign이 먼저 타임아웃되어 정상 에러 처리</li>
 *   <li>Service TimeLimiter ≤ Gateway 타임아웃 → Gateway가 먼저 끊기지 않음</li>
 *   <li>안쪽 → 바깥쪽 순서로 타임아웃이 발생해야 에러가 정상적으로 전파됨</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서는 Feign 타임아웃을 별도 설정하지 않아 기본값(10s/60s)이 적용되었다.
 * MSA-Traffic에서는 Resilience4j TimeLimiter와 조합하여 계단식 타임아웃을 구성한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 서비스 간 HTTP 호출이 없으므로 타임아웃 설정이 불필요했다.
 * MSA에서는 네트워크 호출이 필수이므로 타임아웃 미설정은 스레드 풀 고갈로 이어질 수 있다.
 *
 * ★ Traffic MSA: Feign Client Timeout Configuration
 *
 * Cascading timeout hierarchy:
 *   Gateway: 8s (outermost)
 *   Service: 5s (Resilience4j TimeLimiter)
 *   Feign Client: 3s connect + 5s read (innermost)
 *
 * This prevents Feign from holding threads longer than the
 * service-level timeout, avoiding thread pool exhaustion.
 */
@Configuration
public class FeignConfig {

    /**
     * Feign HTTP 요청 옵션 Bean
     *
     * @return connectTimeout: 3초 (TCP 연결 수립 타임아웃)
     *         readTimeout: 5초 (응답 대기 타임아웃)
     *         followRedirects: true (리다이렉트 자동 추적)
     */
    @Bean
    public Request.Options feignRequestOptions() {
        return new Request.Options(
                3, TimeUnit.SECONDS,   // connectTimeout: TCP 연결 수립 제한 시간
                5, TimeUnit.SECONDS,   // readTimeout: 응답 대기 제한 시간
                true                    // followRedirects: 리다이렉트 허용
        );
    }
}
