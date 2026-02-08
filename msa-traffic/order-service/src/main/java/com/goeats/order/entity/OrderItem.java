package com.goeats.order.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * 주문 항목(OrderItem) 엔티티 - Order 애그리거트의 구성 요소
 *
 * <h3>역할</h3>
 * 하나의 주문에 포함된 개별 메뉴 항목을 표현한다.
 * Order와 다대일(N:1) 관계를 가지며, Order 애그리거트 내에서만 관리된다.
 *
 * <h3>설계 포인트</h3>
 * <ul>
 *   <li>menuId만 저장 (FK 없음): Store 서비스의 메뉴 PK를 참조하지만 외래 키를 걸지 않음
 *       → Database per Service 패턴에서 서비스 간 FK는 사용하지 않음</li>
 *   <li>price를 주문 시점에 스냅샷 저장: 메뉴 가격이 나중에 변경되어도 주문 금액은 불변</li>
 *   <li>FetchType.LAZY: 주문 항목은 Order 조회 시 항상 필요하지 않으므로 지연 로딩</li>
 *   <li>@Setter(AccessLevel.PACKAGE): setOrder()는 같은 패키지의 Order.addItem()에서만 호출</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic / Monolithic</h3>
 * OrderItem 엔티티 자체는 모든 아키텍처에서 동일한 구조를 가진다.
 * 차이점은 menuId의 참조 방식:
 * - Monolithic: Menu 테이블과 같은 DB에 있어 @ManyToOne으로 직접 JOIN 가능
 * - MSA Basic/Traffic: menuId만 저장하고, 메뉴 상세 정보는 Store 서비스에서 Feign으로 조회
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 프록시 생성용 기본 생성자
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_item_seq")
    @SequenceGenerator(name = "order_item_seq", sequenceName = "order_item_seq", allocationSize = 50)
    private Long id;

    // 소속 주문 (다대일 관계, 지연 로딩)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    @Setter(AccessLevel.PACKAGE)  // Order.addItem()에서만 설정 가능 (캡슐화)
    private Order order;

    @Column(nullable = false)
    private Long menuId;           // Store 서비스의 메뉴 PK (FK 없이 ID만 참조)

    @Column(nullable = false)
    private int quantity;          // 주문 수량

    @Column(nullable = false)
    private BigDecimal price;      // 주문 시점의 메뉴 가격 스냅샷

    @Builder
    public OrderItem(Long menuId, int quantity, BigDecimal price) {
        this.menuId = menuId;
        this.quantity = quantity;
        this.price = price;
    }

    /** 소계 계산: 가격 x 수량 */
    public BigDecimal getSubtotal() {
        return price.multiply(BigDecimal.valueOf(quantity));
    }
}
