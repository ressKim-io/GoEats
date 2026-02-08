package com.goeats.delivery.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
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
@EnableSchedulerLock(defaultLockAtMostFor = "30s")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "delivery-service");
    }
}
