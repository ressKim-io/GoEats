package com.goeats.delivery.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 설정 - Redis 기반 스케줄러 분산 락.
 *
 * <p>여러 delivery-service 인스턴스가 동시에 실행될 때, 각 인스턴스의
 * Outbox Relay(@Scheduled)가 동일한 outbox 테이블을 폴링하면
 * Kafka 이벤트가 중복 발행되는 문제가 발생한다.</p>
 *
 * <h3>해결 방식</h3>
 * <p>ShedLock은 Redis에 락 키를 저장하여 <b>오직 하나의 인스턴스</b>만
 * @Scheduled 작업을 실행하도록 보장한다.</p>
 *
 * <h3>동작 흐름</h3>
 * <pre>
 * 인스턴스 A: @Scheduled 실행 시도 → Redis 락 획득 성공 → Outbox 폴링 실행
 * 인스턴스 B: @Scheduled 실행 시도 → Redis 락 획득 실패 → 스킵
 * 인스턴스 C: @Scheduled 실행 시도 → Redis 락 획득 실패 → 스킵
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식은 단일 인스턴스이므로 스케줄러 중복 실행 문제가 없다.
 * MSA에서는 수평 확장(scale-out) 시 동일 스케줄러가 여러 번 실행되므로
 * 분산 락이 필수적이다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서는 ShedLock 없이 Outbox Relay를 실행하여 중복 이벤트 발행 위험이 있다.
 * Traffic 버전에서는 ShedLock으로 정확히 한 번(at-most-once) 실행을 보장한다.</p>
 *
 * <h3>설정값</h3>
 * <ul>
 *   <li>{@code defaultLockAtMostFor = "30s"}: 락의 최대 유지 시간 (인스턴스 장애 시 30초 후 자동 해제)</li>
 *   <li>{@code "delivery-service"}: Redis 락 키의 네임스페이스 (서비스별 격리)</li>
 * </ul>
 *
 * ★ ShedLock: Prevents Duplicate Outbox Relay Execution
 *
 * Problem: When multiple delivery-service instances run,
 *          each instance's OutboxRelay polls the same outbox table,
 *          causing duplicate Kafka event publishing.
 *
 * Solution: ShedLock uses Redis to ensure only ONE instance
 *           executes the @Scheduled relay at any time.
 *
 * The OutboxRelay can be enhanced with @SchedulerLock annotation
 * to use this lock provider.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "30s")  // 락 최대 유지 시간: 30초 (장애 시 자동 해제)
public class ShedLockConfig {

    /**
     * Redis 기반 LockProvider 빈 생성.
     *
     * <p>"delivery-service" 네임스페이스를 사용하여 다른 서비스의 ShedLock 키와 충돌을 방지한다.</p>
     *
     * @param connectionFactory Redis 연결 팩토리 (Spring Boot 자동 설정)
     * @return Redis 기반 LockProvider
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        // "delivery-service": Redis 키 네임스페이스 (다른 서비스와 락 키 충돌 방지)
        return new RedisLockProvider(connectionFactory, "delivery-service");
    }
}
