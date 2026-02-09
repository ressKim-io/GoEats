package com.goeats.common.resilience;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.*;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

/**
 * Resilience4j 메트릭 설정 (Prometheus Metrics Registration)
 *
 * <p>Resilience4j의 4가지 패턴(Circuit Breaker, Retry, Rate Limiter, Bulkhead) 메트릭을
 * Micrometer/Prometheus에 등록하는 설정 클래스.
 * /actuator/prometheus 엔드포인트에서 메트릭을 수집하여 Grafana 등으로 모니터링 가능.</p>
 *
 * <h3>등록되는 메트릭 예시</h3>
 * <pre>
 *   ┌─────────────────────────┬─────────────────────────────────────────────────┐
 *   │ 패턴                    │ Prometheus 메트릭                                │
 *   ├─────────────────────────┼─────────────────────────────────────────────────┤
 *   │ Circuit Breaker         │ resilience4j_circuitbreaker_state               │
 *   │                         │ resilience4j_circuitbreaker_failure_rate        │
 *   │                         │ resilience4j_circuitbreaker_calls_total         │
 *   ├─────────────────────────┼─────────────────────────────────────────────────┤
 *   │ Retry                   │ resilience4j_retry_calls_total                  │
 *   │                         │ (성공/실패/재시도 횟수)                           │
 *   ├─────────────────────────┼─────────────────────────────────────────────────┤
 *   │ Rate Limiter            │ resilience4j_ratelimiter_available_permissions  │
 *   │                         │ resilience4j_ratelimiter_waiting_threads        │
 *   ├─────────────────────────┼─────────────────────────────────────────────────┤
 *   │ Bulkhead                │ resilience4j_bulkhead_available_concurrent_calls│
 *   │                         │ resilience4j_bulkhead_max_allowed_concurrent    │
 *   └─────────────────────────┴─────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Resilience4j 5대 패턴 요약</h3>
 * <ol>
 *   <li><b>Retry</b>: 일시적 장애 시 자동 재시도 (지수 백오프)</li>
 *   <li><b>Circuit Breaker</b>: 실패율 초과 시 요청 차단 → 장애 전파 방지</li>
 *   <li><b>Bulkhead</b>: 동시 요청 수 제한 → 리소스 격리</li>
 *   <li><b>Rate Limiter</b>: 초당 요청 수 제한 → 과부하 방지</li>
 *   <li><b>TimeLimiter</b>: 응답 시간 제한 → 느린 호출 차단 (여기서는 메트릭만 등록)</li>
 * </ol>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 서비스 간 네트워크 호출이 없으므로 Circuit Breaker, Rate Limiter 등
 * 트래픽 제어 패턴이 불필요. 모든 호출이 같은 JVM 내 메서드 호출.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 Resilience4j를 사용하지만 메트릭 등록이 없어 모니터링 불가.
 * MSA-Traffic에서는 이 설정으로 Prometheus + Grafana 기반 실시간 모니터링 지원.</p>
 * <ul>
 *   <li>Circuit Breaker 상태 전이 (CLOSED → OPEN → HALF_OPEN) 시각화</li>
 *   <li>Rate Limiter 거부율 추적으로 적정 임계값 튜닝</li>
 *   <li>Bulkhead 사용률로 동시 요청 제한 최적화</li>
 *   <li>Retry 횟수 추적으로 하위 서비스 안정성 파악</li>
 * </ul>
 *
 * ★ Prometheus Metrics Registration for Resilience4j
 *
 * Registers all Resilience4j pattern metrics with Micrometer/Prometheus:
 * - Circuit Breaker: state transitions, failure rates
 * - Retry: retry attempts count
 * - Rate Limiter: permitted/rejected calls
 * - Bulkhead: available concurrent calls
 *
 * Metrics available at: /actuator/prometheus
 * Example: resilience4j_circuitbreaker_state{name="storeService"} 0
 */
@Configuration
public class ResilienceMetricsConfig {

    /**
     * 생성자 주입으로 Resilience4j 레지스트리들을 Micrometer에 바인딩.
     * 각 Registry에 등록된 인스턴스의 메트릭이 자동으로 Prometheus에 노출됨.
     *
     * @param meterRegistry          Micrometer 메트릭 레지스트리 (Prometheus 연동)
     * @param circuitBreakerRegistry Circuit Breaker 인스턴스 레지스트리
     * @param retryRegistry          Retry 인스턴스 레지스트리
     * @param rateLimiterRegistry    Rate Limiter 인스턴스 레지스트리
     * @param bulkheadRegistry       Bulkhead 인스턴스 레지스트리
     */
    public ResilienceMetricsConfig(
            MeterRegistry meterRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
            RateLimiterRegistry rateLimiterRegistry,
            BulkheadRegistry bulkheadRegistry) {

        // Circuit Breaker 메트릭 바인딩 (상태, 실패율, 호출 수 등)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry)
                .bindTo(meterRegistry);
        // Retry 메트릭 바인딩 (재시도 횟수, 성공/실패 구분)
        TaggedRetryMetrics.ofRetryRegistry(retryRegistry)
                .bindTo(meterRegistry);
        // Rate Limiter 메트릭 바인딩 (허용/거부된 요청 수)
        TaggedRateLimiterMetrics.ofRateLimiterRegistry(rateLimiterRegistry)
                .bindTo(meterRegistry);
        // Bulkhead 메트릭 바인딩 (사용 가능한 동시 호출 수)
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheadRegistry)
                .bindTo(meterRegistry);
    }
}
