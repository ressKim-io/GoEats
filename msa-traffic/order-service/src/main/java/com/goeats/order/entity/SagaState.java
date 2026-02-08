package com.goeats.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Saga 상태 추적 엔티티 - 분산 트랜잭션의 생명주기를 DB에 기록
 *
 * <h3>역할</h3>
 * 하나의 주문 생성이 시작하는 Saga(분산 트랜잭션)의 진행 상태를 추적한다.
 * 어떤 단계까지 진행되었는지, 어디서 실패했는지를 기록하여 디버깅과 모니터링을 가능하게 한다.
 *
 * <h3>Saga 흐름과 SagaState 기록</h3>
 * <pre>
 * 1. 주문 생성 → SagaState(STARTED, "ORDER_CREATED") 저장
 * 2. 결제 완료 이벤트 수신 → SagaState.advanceStep("PAYMENT_COMPLETED")
 * 3. 배달 완료 이벤트 수신 → SagaState.complete() → status=COMPLETED
 *
 * 실패 시:
 * 2-1. 결제 실패 이벤트 수신 → SagaState.startCompensation("잔액 부족")
 * 2-2. 보상 완료 → SagaState.fail("잔액 부족") → status=FAILED
 * </pre>
 *
 * <h3>왜 Saga 상태를 DB에 기록하는가?</h3>
 * <ul>
 *   <li>디버깅: 장애 발생 시 어느 단계에서 멈췄는지 즉시 확인</li>
 *   <li>모니터링: STARTED 상태가 오래된 Saga → 문제 감지</li>
 *   <li>복구: COMPENSATING 상태의 Saga를 수동 또는 자동으로 재처리</li>
 *   <li>감사(Audit): 주문의 전체 처리 이력을 추적</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 SagaState 엔티티가 없었다.
 * Kafka 이벤트만으로 Saga를 진행했기 때문에, 이벤트가 유실되거나 처리 실패 시
 * 어디까지 진행되었는지 파악할 수 없었다.
 * MSA-Traffic에서는 SagaState를 DB에 저장하여 Saga의 전체 수명 주기를 추적한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 @Transactional 하나로 원자적 트랜잭션을 보장했으므로
 * 중간 상태 추적이 불필요했다 (성공 또는 롤백).
 * MSA에서는 각 서비스의 로컬 트랜잭션이 독립적이므로
 * Saga 상태 추적으로 전체 흐름의 일관성을 관리해야 한다.
 *
 * ★ Saga State Entity - Tracks the lifecycle of a distributed transaction
 *
 * vs Basic MSA: No saga tracking → impossible to debug failed workflows
 *
 * Each order creation starts a saga that spans:
 *   Order → Payment → Delivery
 *
 * SagaState records:
 *   - Current step in the workflow
 *   - Status transitions (STARTED → COMPLETED or FAILED)
 *   - Failure reasons for debugging
 *
 * This entity makes the saga state queryable and auditable.
 */
@Entity
@Table(name = "saga_states", indexes = {
        @Index(name = "idx_saga_order_id", columnList = "orderId"),   // 주문 ID로 Saga 조회
        @Index(name = "idx_saga_status", columnList = "status")       // 상태별 Saga 조회 (모니터링)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SagaState {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "saga_state_seq")
    @SequenceGenerator(name = "saga_state_seq", sequenceName = "saga_state_seq", allocationSize = 50)
    private Long id;

    // Saga 고유 식별자 (UUID): 분산 환경에서 Saga를 유일하게 식별
    @Column(nullable = false, unique = true)
    private String sagaId;

    // Saga 유형: "CREATE_ORDER" 등 (향후 다양한 Saga 유형 확장 가능)
    @Column(nullable = false)
    private String sagaType;

    // 현재 Saga 상태 (STARTED → COMPENSATING → FAILED 또는 COMPLETED)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    // 현재 진행 중인 단계 (예: "ORDER_CREATED", "PAYMENT_COMPLETED", "COMPLETED")
    @Column(nullable = false)
    private String currentStep;

    // 실패 사유 (보상 트랜잭션 또는 최종 실패 시 기록)
    private String failureReason;

    // 이 Saga가 관리하는 주문 ID
    @Column(nullable = false)
    private Long orderId;

    @CreatedDate
    private LocalDateTime createdAt;    // Saga 시작 시각

    @LastModifiedDate
    private LocalDateTime updatedAt;    // 마지막 상태 변경 시각

    @Builder
    public SagaState(String sagaId, String sagaType, Long orderId) {
        this.sagaId = sagaId;
        this.sagaType = sagaType;
        this.orderId = orderId;
        this.status = SagaStatus.STARTED;             // 초기 상태: STARTED
        this.currentStep = "ORDER_CREATED";            // 초기 단계: 주문 생성
    }

    /** Saga를 다음 단계로 진행 (예: "PAYMENT_COMPLETED", "DELIVERY_STARTED") */
    public void advanceStep(String step) {
        this.currentStep = step;
    }

    /** Saga 완료: 모든 단계가 성공적으로 종료 */
    public void complete() {
        this.status = SagaStatus.COMPLETED;
        this.currentStep = "COMPLETED";
    }

    /** 보상 트랜잭션 시작: 특정 단계에서 실패하여 이전 단계를 롤백 */
    public void startCompensation(String reason) {
        this.status = SagaStatus.COMPENSATING;
        this.failureReason = reason;
    }

    /** Saga 최종 실패: 보상 트랜잭션까지 완료되어 Saga 종료 */
    public void fail(String reason) {
        this.status = SagaStatus.FAILED;
        this.failureReason = reason;
    }
}
