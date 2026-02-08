package com.goeats.common.outbox;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Auto-configuration for Outbox module.
 * Each service that includes common-outbox gets the entity, repository,
 * service, and relay automatically registered.
 */
@Configuration
@ComponentScan(basePackages = "com.goeats.common.outbox")
@EntityScan(basePackages = "com.goeats.common.outbox")
@EnableJpaRepositories(basePackages = "com.goeats.common.outbox")
public class OutboxConfig {
}
