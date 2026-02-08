package com.goeats.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * ★ MSA: Only stores userId (no JPA relationship).
     * User info is fetched via OpenFeign HTTP call when needed.
     *
     * Compare with Monolithic: @ManyToOne(fetch = LAZY) User user
     * with direct JPA JOIN.
     */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long storeId;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    private String deliveryAddress;
    private String paymentMethod;

    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public Order(Long userId, Long storeId, String deliveryAddress, String paymentMethod) {
        this.userId = userId;
        this.storeId = storeId;
        this.deliveryAddress = deliveryAddress;
        this.paymentMethod = paymentMethod;
        this.status = OrderStatus.CREATED;
        this.totalAmount = BigDecimal.ZERO;
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
        recalculateTotal();
    }

    public void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
