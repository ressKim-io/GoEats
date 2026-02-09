package com.goeats.payment.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 설정 - Redis 기반 스케줄러 분산 락.
 *
 * <p>다중 인스턴스에서 OutboxRelay(@Scheduled)가 동시에 실행되는 것을 방지한다.
 * Redis에 락 키를 저장하여 오직 하나의 인스턴스만 스케줄러를 실행하도록 보장.</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "30s")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "payment-service");
    }
}
