package com.goeats.delivery.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 설정 클래스.
 *
 * <p>{@code @EnableJpaAuditing}을 활성화하여 엔티티의 {@code @CreatedDate}, {@code @LastModifiedDate} 등
 * 감사(Auditing) 어노테이션이 자동으로 동작하도록 한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 하나의 JPA 설정으로 모든 도메인 엔티티를 관리한다.
 * MSA에서는 각 서비스가 독립된 DB를 사용하므로 서비스별로 JPA 설정이 필요하다.</p>
 *
 * <h3>사용되는 곳</h3>
 * <ul>
 *   <li>{@link com.goeats.delivery.entity.Delivery} - {@code createdAt} 필드 자동 생성</li>
 * </ul>
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
