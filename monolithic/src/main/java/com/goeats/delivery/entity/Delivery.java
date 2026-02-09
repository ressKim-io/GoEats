package com.goeats.delivery.entity;

import com.goeats.order.entity.Order;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries", indexes = {
        @Index(name = "idx_delivery_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "delivery_seq")
    @SequenceGenerator(name = "delivery_seq", sequenceName = "delivery_seq", allocationSize = 50)
    private Long id;

    @Version
    private Long version;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    private String riderName;

    private String riderPhone;

    @Column(nullable = false)
    private String deliveryAddress;

    private LocalDateTime estimatedDeliveryTime;

    @CreatedDate
    private LocalDateTime createdAt;

    @Builder
    public Delivery(Order order, String deliveryAddress) {
        this.order = order;
        this.deliveryAddress = deliveryAddress;
        this.status = DeliveryStatus.WAITING;
    }

    public void assignRider(String riderName, String riderPhone) {
        this.riderName = riderName;
        this.riderPhone = riderPhone;
        this.status = DeliveryStatus.RIDER_ASSIGNED;
        this.estimatedDeliveryTime = LocalDateTime.now().plusMinutes(30);
    }

    public void pickUp() {
        this.status = DeliveryStatus.PICKED_UP;
    }

    public void startDelivery() {
        this.status = DeliveryStatus.DELIVERING;
    }

    public void complete() {
        this.status = DeliveryStatus.DELIVERED;
    }

    public void cancel() {
        this.status = DeliveryStatus.CANCELLED;
    }
}
