package com.goeats.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ★ MSA: Only stores orderId (no JPA relationship).
     * Order is in a different database (order_db).
     *
     * Compare with Monolithic: @OneToOne Order order
     * with direct JPA relationship in the same database.
     */
    @Column(nullable = false, unique = true)
    private Long orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String paymentMethod;

    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public Payment(Long orderId, BigDecimal amount, String paymentMethod) {
        this.orderId = orderId;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.PENDING;
    }

    public void complete() { this.status = PaymentStatus.COMPLETED; }
    public void fail() { this.status = PaymentStatus.FAILED; }
    public void refund() { this.status = PaymentStatus.REFUNDED; }
}
