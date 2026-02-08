package com.goeats.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
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
@Table(name = "saga_states")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class SagaState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String sagaId;

    @Column(nullable = false)
    private String sagaType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaStatus status;

    @Column(nullable = false)
    private String currentStep;

    private String failureReason;

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
        this.currentStep = "ORDER_CREATED";
    }

    public void advanceStep(String step) {
        this.currentStep = step;
    }

    public void complete() {
        this.status = SagaStatus.COMPLETED;
        this.currentStep = "COMPLETED";
    }

    public void startCompensation(String reason) {
        this.status = SagaStatus.COMPENSATING;
        this.failureReason = reason;
    }

    public void fail(String reason) {
        this.status = SagaStatus.FAILED;
        this.failureReason = reason;
    }
}
