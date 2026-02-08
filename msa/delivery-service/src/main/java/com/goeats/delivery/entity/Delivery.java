package com.goeats.delivery.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ★ MSA: Only stores orderId (separate database)
    @Column(nullable = false, unique = true)
    private Long orderId;

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
    public Delivery(Long orderId, String deliveryAddress) {
        this.orderId = orderId;
        this.deliveryAddress = deliveryAddress;
        this.status = DeliveryStatus.WAITING;
    }

    public void assignRider(String riderName, String riderPhone) {
        this.riderName = riderName;
        this.riderPhone = riderPhone;
        this.status = DeliveryStatus.RIDER_ASSIGNED;
        this.estimatedDeliveryTime = LocalDateTime.now().plusMinutes(30);
    }

    public void pickUp() { this.status = DeliveryStatus.PICKED_UP; }
    public void startDelivery() { this.status = DeliveryStatus.DELIVERING; }
    public void complete() { this.status = DeliveryStatus.DELIVERED; }
    public void cancel() { this.status = DeliveryStatus.CANCELLED; }
}
