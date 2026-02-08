package com.goeats.common.outbox;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Outbox 모듈 자동 구성 (Auto-configuration for Outbox module)
 *
 * <p>common-outbox 모듈을 의존성으로 추가한 서비스에서 Outbox 관련 빈들이
 * 자동으로 등록되도록 하는 설정 클래스.</p>
 *
 * <h3>자동 등록되는 컴포넌트</h3>
 * <ul>
 *   <li>{@link OutboxEvent}: JPA 엔티티 (outbox_events 테이블)</li>
 *   <li>{@link OutboxEventRepository}: 미발행 이벤트 조회 리포지토리</li>
 *   <li>{@link OutboxService}: 비즈니스 트랜잭션 내에서 이벤트 저장</li>
 *   <li>{@link OutboxRelay}: 주기적으로 미발행 이벤트를 Kafka로 전송하는 릴레이</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 @Transactional + ApplicationEventPublisher로 이벤트 처리.
 * 같은 프로세스 내에서 동기 호출이므로 Outbox 패턴 자체가 불필요.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에는 Outbox 모듈이 존재하지 않음. 각 서비스에서
 * KafkaTemplate.send()를 직접 호출하여 이벤트 유실 위험이 있음.
 * MSA-Traffic에서는 이 모듈을 통해 Transactional Outbox 패턴을 공통화.</p>
 *
 * <h3>사용법</h3>
 * <p>각 서비스의 build.gradle에서 의존성 추가:</p>
 * <pre>
 *   implementation project(':common:common-outbox')
 * </pre>
 * <p>별도 설정 없이 @ComponentScan, @EntityScan, @EnableJpaRepositories가
 * 자동으로 outbox 패키지를 스캔함.</p>
 *
 * Auto-configuration for Outbox module.
 * Each service that includes common-outbox gets the entity, repository,
 * service, and relay automatically registered.
 */
@Configuration
@ComponentScan(basePackages = "com.goeats.common.outbox")       // OutboxService, OutboxRelay 빈 등록
@EntityScan(basePackages = "com.goeats.common.outbox")          // OutboxEvent JPA 엔티티 스캔
@EnableJpaRepositories(basePackages = "com.goeats.common.outbox") // OutboxEventRepository 활성화
public class OutboxConfig {
}
