package com.goeats.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 설정 클래스.
 *
 * <p>@EnableJpaAuditing을 활성화하여 엔티티의 {@code @CreatedDate}, {@code @LastModifiedDate} 등
 * 감사(Auditing) 어노테이션이 자동으로 동작하도록 한다.</p>
 *
 * <h3>Payment 서비스에서의 활용</h3>
 * <ul>
 *   <li>{@code Payment.createdAt} - 결제 생성 시각 자동 기록 (@CreatedDate)</li>
 *   <li>{@code ProcessedEvent.processedAt}은 생성자에서 직접 설정하지만,
 *       향후 Auditing으로 전환 가능</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic과 동일한 설정이다. 다만 Traffic에서는 Outbox 엔티티(common-outbox 모듈)도
 * JPA Auditing을 활용하여 이벤트 생성 시각을 자동으로 기록한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 하나의 JpaConfig로 모든 도메인의 Auditing을 관리했다.
 * MSA에서는 각 서비스가 독립된 DB를 가지므로, 서비스마다 별도의 JpaConfig가 필요하다.</p>
 */
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
