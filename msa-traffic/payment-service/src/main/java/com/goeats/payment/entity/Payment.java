package com.goeats.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 엔티티.
 *
 * <p>주문에 대한 결제 정보를 저장하는 핵심 엔티티로, 결제 금액, 상태, 결제 수단 등을 관리한다.</p>
 *
 * <h3>적용된 트래픽 패턴</h3>
 * <ul>
 *   <li><b>@Version (낙관적 락)</b> - 동시에 같은 결제를 수정하려 할 때 OptimisticLockException 발생.
 *       예: 환불 처리와 상태 업데이트가 동시에 발생하는 경우 데이터 정합성을 보장한다.</li>
 *   <li><b>idempotencyKey</b> - 클라이언트가 전달한 멱등성 키로 중복 결제를 방지한다.
 *       unique 제약조건이 걸려있어 DB 레벨에서도 중복 삽입을 차단한다.</li>
 *   <li><b>@SequenceGenerator(allocationSize=50)</b> - ID 채번 시 시퀀스를 50개씩 미리 할당하여
 *       DB 호출 횟수를 줄이고 성능을 최적화한다.</li>
 *   <li><b>@Index(idx_payment_status)</b> - status 컬럼 인덱스로 상태별 결제 조회 성능을 향상시킨다.</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>MSA Basic에서는 orderId만으로 중복 체크했고, @Version과 idempotencyKey가 없었다.
 * Traffic에서는 낙관적 락 + 멱등성 키로 이중 보호하여, 동시성 문제와 중복 결제를 모두 방지한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>Monolithic에서는 @Transactional + 비관적 락(Pessimistic Lock)으로 동시성을 제어했다.
 * 단일 DB에서는 비관적 락이 효과적이지만, MSA의 분산 환경에서는 서비스 간 DB가 분리되어 있으므로
 * 낙관적 락(@Version) + 멱등성 키(idempotencyKey) 조합이 더 적합하다.</p>
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payment_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_seq")
    @SequenceGenerator(name = "payment_seq", sequenceName = "payment_seq", allocationSize = 50)
    private Long id;

    // 낙관적 락 버전 필드 - 동시 수정 시 OptimisticLockException 발생
    @Version
    private Long version;

    // 주문 ID - 하나의 주문에 하나의 결제만 존재 (unique 제약)
    @Column(nullable = false, unique = true)
    private Long orderId;

    // 결제 금액
    @Column(nullable = false)
    private BigDecimal amount;

    // 결제 상태 (PENDING -> COMPLETED/FAILED -> REFUNDED)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    // 결제 수단 (예: CARD, CASH 등)
    private String paymentMethod;

    // ★ Traffic MSA: idempotencyKey for duplicate payment prevention
    // 멱등성 키 - 같은 키로 중복 결제 요청 시 기존 결제를 반환 (unique 제약으로 DB 레벨 보호)
    @Column(unique = true)
    private String idempotencyKey;

    // JPA Auditing에 의해 자동으로 결제 생성 시각이 기록됨
    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public Payment(Long orderId, BigDecimal amount, String paymentMethod, String idempotencyKey) {
        this.orderId = orderId;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.idempotencyKey = idempotencyKey;
        this.status = PaymentStatus.PENDING;  // 초기 상태는 항상 PENDING
    }

    /** 결제 완료 처리 - PG사 승인 성공 시 호출 */
    public void complete() { this.status = PaymentStatus.COMPLETED; }

    /** 결제 실패 처리 - PG사 승인 실패 시 호출 */
    public void fail() { this.status = PaymentStatus.FAILED; }

    /** 환불 처리 - Saga 보상 트랜잭션 또는 수동 환불 시 호출 */
    public void refund() { this.status = PaymentStatus.REFUNDED; }
}
