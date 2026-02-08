package com.goeats.payment.repository;

import com.goeats.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 결제 엔티티에 대한 JPA Repository 인터페이스.
 *
 * <p>결제 서비스 전용 데이터베이스에 접근합니다.
 * MSA에서는 각 서비스가 독립된 DB를 가지므로(Database per Service 패턴),
 * 이 레포지토리는 결제 테이블에만 접근할 수 있습니다.</p>
 *
 * <p>주요 쿼리:
 * <ul>
 *   <li>findByOrderId: orderId로 결제 정보를 조회 (주문-결제는 1:1 관계)</li>
 *   <li>existsByOrderId: 해당 주문에 대한 결제가 이미 존재하는지 확인 (중복 결제 방지)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 하나의 DB에 Order, Payment 테이블이 함께 존재 → JOIN 가능
 * - MSA: 각 서비스 별도 DB → JOIN 불가, orderId를 외래키 대신 참조 ID로 사용
 *   → 데이터 일관성은 Kafka 이벤트(Saga 패턴)로 보장</p>
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    /** 주문 ID로 결제 정보를 조회합니다 (주문과 결제는 1:1 관계) */
    Optional<Payment> findByOrderId(Long orderId);

    /** 해당 주문에 대한 결제가 이미 존재하는지 확인합니다 (중복 결제 방지용, 멱등성 보장) */
    boolean existsByOrderId(Long orderId);
}
