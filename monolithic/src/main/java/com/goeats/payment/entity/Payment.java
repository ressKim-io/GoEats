package com.goeats.payment.entity;

import com.goeats.order.entity.Order;
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
     * ★ Monolithic: Direct JPA relationship to Order entity.
     * Payment references Order in the same database.
     *
     * Compare with MSA: Payment service only stores orderId (Long),
     * and listens for OrderCreatedEvent via Kafka.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String paymentMethod;

    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public Payment(Order order, BigDecimal amount, String paymentMethod) {
        this.order = order;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.status = PaymentStatus.PENDING;
    }

    public void complete() {
        this.status = PaymentStatus.COMPLETED;
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
    }

    public void refund() {
        this.status = PaymentStatus.REFUNDED;
    }
}
