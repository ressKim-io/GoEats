package com.goeats.order.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문(Order) 엔티티 - 주문 도메인의 애그리거트 루트(Aggregate Root)
 *
 * <h3>역할</h3>
 * 하나의 주문을 표현하며, 주문 항목(OrderItem) 컬렉션을 관리한다.
 * 애그리거트 루트로서 주문의 일관성(합계 계산, 상태 변경)을 보장한다.
 *
 * <h3>설계 포인트</h3>
 * <ul>
 *   <li>@Version: 낙관적 락(Optimistic Lock) - 동시 주문 상태 변경 충돌 감지</li>
 *   <li>SEQUENCE 전략 + allocationSize=50: ID 채번 최적화 (DB 왕복 감소)</li>
 *   <li>복합 인덱스(status, createdAt): 주문 목록 조회 성능 최적화</li>
 *   <li>CascadeType.ALL + orphanRemoval: OrderItem 생명주기를 Order가 관리</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic의 Order 엔티티와 구조적으로 유사하지만, MSA-Traffic에서는:
 * - @Version 낙관적 락 추가 → 동시 상태 변경 시 충돌 감지
 * - 인덱스 추가 → 대량 트래픽 환경에서의 쿼리 성능 보장
 * - SEQUENCE 전략 + allocationSize=50 → 고성능 ID 채번
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 Order와 Store, Payment가 같은 DB에 있어 JOIN으로 조회 가능했다.
 * MSA에서는 각 서비스가 독립 DB를 가지므로, storeId만 FK 없이 저장하고
 * Store 정보는 OpenFeign으로 조회한다 (Database per Service 패턴).
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_order_user_id", columnList = "userId"),
        @Index(name = "idx_order_store_id", columnList = "storeId"),
        @Index(name = "idx_order_status", columnList = "status"),
        // 복합 인덱스: "진행 중인 주문 목록" 등 상태+시간 기반 조회 최적화
        @Index(name = "idx_order_status_created", columnList = "status, createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 프록시 생성용 기본 생성자 (외부 사용 금지)
@EntityListeners(AuditingEntityListener.class)       // @CreatedDate 자동 설정을 위한 리스너
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")
    @SequenceGenerator(name = "order_seq", sequenceName = "order_seq", allocationSize = 50)
    private Long id;

    // 낙관적 락(Optimistic Lock): 동시에 같은 주문 상태를 변경하면 OptimisticLockException 발생
    @Version
    private Long version;

    @Column(nullable = false)
    private Long userId;     // 주문자 ID (User 서비스의 사용자 PK 참조, FK 없음)

    @Column(nullable = false)
    private Long storeId;    // 가게 ID (Store 서비스의 가게 PK 참조, FK 없음)

    // 주문 항목 목록: Order가 애그리거트 루트로서 OrderItem의 생명주기를 관리
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    // 주문 상태 (CREATED → PAYMENT_PENDING → PAID → PREPARING → DELIVERING → DELIVERED)
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    // 주문 총액 (OrderItem 추가 시 자동 재계산)
    @Column(nullable = false)
    private BigDecimal totalAmount;

    private String deliveryAddress;    // 배달 주소
    private String paymentMethod;      // 결제 수단 (CARD, CASH 등)

    @CreatedDate
    private LocalDateTime createdAt;   // 주문 생성 시각 (JPA Auditing 자동 설정)

    @Builder
    public Order(Long userId, Long storeId, String deliveryAddress, String paymentMethod) {
        this.userId = userId;
        this.storeId = storeId;
        this.deliveryAddress = deliveryAddress;
        this.paymentMethod = paymentMethod;
        this.status = OrderStatus.CREATED;       // 초기 상태: CREATED
        this.totalAmount = BigDecimal.ZERO;       // 초기 금액: 0 (아이템 추가 시 재계산)
    }

    /** 주문 항목 추가 및 양방향 관계 설정 + 총액 재계산 */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);       // 양방향 관계 설정 (OrderItem → Order)
        recalculateTotal();        // 아이템 추가 시 총액 자동 재계산
    }

    /** 주문 상태 변경 (Saga 진행에 따라 호출) */
    public void updateStatus(OrderStatus newStatus) {
        this.status = newStatus;
    }

    /** 전체 주문 항목의 소계(subtotal)를 합산하여 총액 재계산 */
    private void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order that)) return false;
        return id != null && id.equals(that.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
