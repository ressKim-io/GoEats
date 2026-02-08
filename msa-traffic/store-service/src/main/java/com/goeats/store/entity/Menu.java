package com.goeats.store.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 메뉴(Menu) 엔티티 - 가게에 속한 개별 메뉴 정보를 관리한다.
 *
 * <p>메뉴의 이름, 가격, 설명, 판매 가능 여부를 포함하며,
 * Store(가게)와 N:1 관계로 연결된다.</p>
 *
 * <h3>★ Serializable 구현 (핵심!)</h3>
 * <p>{@code implements Serializable}은 Redis 캐시에 저장하기 위해 필수이다.
 * Store 엔티티가 menus 목록을 포함하여 Redis에 저장될 때,
 * Menu도 함께 직렬화되어야 하므로 Serializable 구현이 필요하다.</p>
 *
 * <h3>@JsonIgnore - 순환 참조 방지</h3>
 * <p>Store → Menu → Store의 순환 참조를 방지하기 위해
 * Menu의 store 필드에 @JsonIgnore를 적용한다.
 * JSON 직렬화(API 응답, Redis 캐시) 시 store 필드가 제외된다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시)를 사용하므로 Serializable이 필요 없다.
 * 또한 JPA 세션 범위 안에서 Lazy Loading이 자연스럽게 동작한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서도 Serializable을 구현하지만, 인덱스 최적화가 없다.
 * Traffic 버전에서는 {@code idx_menu_store_available} 복합 인덱스를 추가하여
 * "가게별 판매 가능 메뉴" 조회 성능을 최적화한다.</p>
 *
 * <h3>인덱스 전략</h3>
 * <ul>
 *   <li>{@code idx_menu_store_available}: (store_id, available) 복합 인덱스
 *       → findByStoreIdAndAvailableTrue() 쿼리 최적화</li>
 * </ul>
 */
@Entity
@Table(name = "menus", indexes = {
        // 가게별 판매 가능 메뉴 조회 최적화 (가장 빈번한 쿼리)
        @Index(name = "idx_menu_store_available", columnList = "store_id, available")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 프록시 생성용
public class Menu implements Serializable {  // ★ Redis 캐시 저장을 위한 Serializable 구현

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "menu_seq")
    @SequenceGenerator(name = "menu_seq", sequenceName = "menu_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;  // 메뉴 이름

    @Column(nullable = false)
    private BigDecimal price;  // 메뉴 가격 (BigDecimal: 금액 연산의 정밀도 보장)

    private String description;  // 메뉴 설명

    private boolean available;  // 판매 가능 여부 (품절 관리)

    @ManyToOne(fetch = FetchType.LAZY)  // N:1 관계, LAZY 로딩 (불필요한 Store 조회 방지)
    @JoinColumn(name = "store_id")
    @Setter(AccessLevel.PACKAGE)  // Store.addMenu()에서만 설정 가능 (패키지 접근 제한)
    @JsonIgnore  // ★ 순환 참조 방지: Menu → Store → Menu 무한 루프 차단
    private Store store;

    @Builder
    public Menu(String name, BigDecimal price, String description, boolean available) {
        this.name = name;
        this.price = price;
        this.description = description;
        this.available = available;
    }
}
