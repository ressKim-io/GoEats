package com.goeats.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Saga 상태 추적 엔티티 - Orchestration Saga의 중앙 상태 관리
 *
 * <h3>역할</h3>
 * Orchestrator가 관리하는 Saga의 현재 단계(SagaStep)와 상태(SagaStatus)를 DB에 기록한다.
 * Orchestrator는 SagaState를 읽고 다음 커맨드를 결정한다.
 *
 * <h3>★ Choreography → Orchestration 변경점</h3>
 * <pre>
 * Before (Choreography):
 *   - currentStep: String ("ORDER_CREATED", "PAYMENT_COMPLETED")
 *   - 전이 검증 없음, 관찰 전용
 *   - 각 서비스가 독립적으로 이벤트를 구독/발행
 *
 * After (Orchestration):
 *   - currentStep: SagaStep enum (PAYMENT_PENDING → PAYMENT_COMPLETED → ...)
 *   - canTransitionTo()로 유효 전이 검증
 *   - Orchestrator가 SagaState를 기반으로 다음 단계를 제어
 * </pre>
 *
 * <h3>Saga 흐름과 SagaStep 전이</h3>
 * <pre>
 * 정상 흐름:
 *   PAYMENT_PENDING → PAYMENT_COMPLETED → DELIVERY_PENDING → COMPLETED
 *
 * 결제 실패:
 *   PAYMENT_PENDING → FAILED
 *
 * 배달 실패 → 보상:
 *   DELIVERY_PENDING → COMPENSATING_PAYMENT → FAILED
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 @Transactional 하나로 원자적 트랜잭션을 보장했으므로
 * 중간 상태 추적이 불필요했다 (성공 또는 롤백).
 *
 * <h3>★ vs MSA Basic (Choreography)</h3>
 * Choreography에서는 SagaState가 관찰 전용이었다 (흐름 제어 불가).
 * Orchestration에서는 SagaState가 Orchestrator의 의사결정 기반이다.
 *
 * ★ Orchestration Saga State Entity - Central state machine
 *
 * currentStep: SagaStep enum with transition validation
 * Orchestrator reads SagaState to decide the next command
 */
@Entity
@Table(name = "saga_states", indexes = {
        @Index(name = "idx_saga_order_id", columnList = "orderId"),
        @Index(name = "idx_saga_status", columnList = "status"),
        @Index(name = "idx_saga_saga_id", columnList = "sagaId")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SagaState {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "saga_state_seq")
    @SequenceGenerator(name = "saga_state_seq", sequenceName = "saga_state_seq", allocationSize = 50)
    private Long id;

    // Saga unique identifier (UUID): uniquely identifies a saga in distributed environment
    @Column(nullable = false, unique = true)
    private String sagaId;

    // Saga type: "CREATE_ORDER" etc.
    @Column(nullable = false)
    private String sagaType;

    // Current saga status (STARTED → COMPENSATING → FAILED or COMPLETED)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    // ★ Orchestration: type-safe step enum with transition validation
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStep currentStep;

    // Failure reason (recorded during compensation or final failure)
    private String failureReason;

    // Order ID managed by this saga
    @Column(nullable = false)
    private Long orderId;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    @Builder
    public SagaState(String sagaId, String sagaType, Long orderId) {
        this.sagaId = sagaId;
        this.sagaType = sagaType;
        this.orderId = orderId;
        this.status = SagaStatus.STARTED;
        this.currentStep = SagaStep.PAYMENT_PENDING;  // ★ Orchestration: starts with PAYMENT_PENDING
    }

    /**
     * ★ Orchestration: Transition to next step with validation.
     * Throws IllegalStateException if the transition is not valid.
     */
    public void transitionTo(SagaStep nextStep) {
        if (!this.currentStep.canTransitionTo(nextStep)) {
            throw new IllegalStateException(
                    String.format("Invalid saga transition: %s → %s (sagaId=%s)",
                            this.currentStep, nextStep, this.sagaId));
        }
        this.currentStep = nextStep;
    }

    /** Saga completed: all steps succeeded */
    public void complete() {
        transitionTo(SagaStep.COMPLETED);
        this.status = SagaStatus.COMPLETED;
    }

    /** Start compensation: a step failed, begin rollback */
    public void startCompensation(String reason) {
        this.status = SagaStatus.COMPENSATING;
        this.failureReason = reason;
        transitionTo(SagaStep.COMPENSATING_PAYMENT);
    }

    /** Saga final failure: compensation completed */
    public void fail(String reason) {
        this.status = SagaStatus.FAILED;
        this.failureReason = reason;
        this.currentStep = SagaStep.FAILED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SagaState that)) return false;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
