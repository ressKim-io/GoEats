package com.goeats.payment.repository;

import com.goeats.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 결제 레포지토리 - Payment 엔티티의 데이터 접근 계층.
 *
 * <p>결제 정보의 CRUD와 다양한 조건별 조회 메서드를 제공한다.</p>
 *
 * <h3>주요 쿼리 메서드</h3>
 * <ul>
 *   <li>{@code findByOrderId} - 주문 ID로 결제 조회 (환불, 결제 상태 확인 시 사용)</li>
 *   <li>{@code existsByOrderId} - 주문에 대한 결제 존재 여부 확인 (중복 결제 방지 1차 체크)</li>
 *   <li>{@code findByIdempotencyKey} - 멱등성 키로 결제 조회 (중복 결제 방지 2차 체크)
 *       ★ Traffic MSA에서 추가된 메서드</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 findByOrderId, existsByOrderId만 존재했다.
 * Traffic에서는 findByIdempotencyKey가 추가되어 이중 멱등성 체크가 가능하다.
 * orderId 중복 체크 → idempotencyKey 중복 체크 → 신규 결제 생성 순서로 처리한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 단일 DB 내에서 @Transactional로 묶여 동시성 제어가 간단했다.
 * MSA에서는 Kafka 이벤트의 중복 전달, 클라이언트 재시도 등 다양한 중복 시나리오가 있어
 * 여러 조건으로 중복을 체크해야 한다.</p>
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** 주문 ID로 결제 조회 - 환불, 상태 확인 등에 사용 */
    Optional<Payment> findByOrderId(Long orderId);

    /** 주문 ID로 결제 존재 여부 확인 - 중복 결제 방지 1차 체크 (빠른 존재 확인) */
    boolean existsByOrderId(Long orderId);

    // ★ Traffic MSA: find by idempotencyKey for duplicate detection
    /** 멱등성 키로 결제 조회 - 중복 결제 방지 2차 체크 (클라이언트 재시도 대응) */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
