package com.goeats.order.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 설정
 *
 * <h3>역할</h3>
 * @EnableJpaAuditing을 활성화하여 엔티티의 생성/수정 시간을 자동으로 기록한다.
 * Order.createdAt (@CreatedDate), SagaState.createdAt, SagaState.updatedAt (@LastModifiedDate) 등에 적용.
 *
 * <h3>AuditingEntityListener 동작 원리</h3>
 * <pre>
 * 1. 엔티티에 @EntityListeners(AuditingEntityListener.class) 선언
 * 2. @CreatedDate 필드 → persist 시 현재 시간 자동 설정
 * 3. @LastModifiedDate 필드 → update 시 현재 시간 자동 갱신
 * </pre>
 *
 * <h3>★ vs MSA Basic / Monolithic</h3>
 * JPA Auditing 설정은 Monolithic, MSA Basic, MSA-Traffic 모두 동일하다.
 * 이 설정은 인프라 패턴이 아닌 JPA 기본 기능이므로 아키텍처와 무관하게 적용된다.
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
