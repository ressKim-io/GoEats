package com.goeats.delivery.repository;

import com.goeats.delivery.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 배달 엔티티에 대한 JPA Repository 인터페이스.
 *
 * <p>배달 서비스 전용 데이터베이스에 접근합니다.
 * MSA에서는 Database per Service 패턴을 적용하여
 * 배달 서비스만의 독립된 DB를 사용합니다.</p>
 *
 * <p>orderId를 통해 배달 정보를 조회합니다.
 * MSA에서는 Order 테이블과 JOIN이 불가능하므로,
 * orderId를 참조 ID로 저장하여 서비스 간 연관관계를 유지합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: Order와 Delivery가 같은 DB → @ManyToOne 관계로 JOIN 가능
 * - MSA: 별도 DB → orderId(Long)로만 참조, JOIN 대신 API 호출로 조합
 *   → 데이터 정합성은 Kafka 이벤트(Saga 패턴)로 보장</p>
 */
public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
    /** 주문 ID로 배달 정보를 조회합니다 (주문과 배달은 1:1 관계) */
    Optional<Delivery> findByOrderId(Long orderId);
}
