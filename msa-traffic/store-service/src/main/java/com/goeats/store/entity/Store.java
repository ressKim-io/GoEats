package com.goeats.store.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 가게(Store) 엔티티 - 가게 정보와 메뉴 목록을 관리한다.
 *
 * <p>가게의 기본 정보(이름, 주소, 전화번호, 영업 여부)와
 * 해당 가게에 속한 메뉴(Menu) 목록을 포함한다.</p>
 *
 * <h3>★ Serializable 구현 (핵심!)</h3>
 * <p>{@code implements Serializable}은 Redis 캐시에 저장하기 위해 필수이다.
 * Redis는 객체를 직렬화하여 바이트 배열로 변환 후 저장하는데,
 * Serializable 인터페이스가 없으면 직렬화에 실패한다.</p>
 *
 * <pre>
 * [저장 흐름] Store 객체 → JSON 직렬화 → Redis 저장
 * [조회 흐름] Redis 조회 → JSON 역직렬화 → Store 객체
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Caffeine(로컬 캐시)를 사용하므로 Serializable이 필요 없다.
 * 로컬 캐시는 객체 참조를 그대로 저장하기 때문이다.
 * MSA에서는 Redis(네트워크 캐시)를 사용하므로 반드시 직렬화가 가능해야 한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에서도 Redis 캐시를 사용하지만, Cache Warming이 없어
 * Cold Start 시 모든 요청이 DB를 직접 조회한다.
 * Traffic 버전에서는 CacheWarmingRunner가 서비스 시작 시 영업중 가게를 미리 로딩한다.</p>
 *
 * <h3>성능 최적화</h3>
 * <ul>
 *   <li>SEQUENCE 전략 + allocationSize=50: ID 채번 시 DB 왕복 횟수를 1/50로 감소</li>
 *   <li>@OneToMany(cascade=ALL): 가게 저장 시 메뉴도 함께 저장 (편의 메서드 addMenu 제공)</li>
 * </ul>
 */
@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 프록시 생성용 기본 생성자
public class Store implements Serializable {  // ★ Redis 캐시 저장을 위한 Serializable 구현

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "store_seq")
    @SequenceGenerator(name = "store_seq", sequenceName = "store_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;  // 가게 이름

    @Column(nullable = false)
    private String address;  // 가게 주소

    @Column(nullable = false)
    private String phone;  // 가게 전화번호

    private boolean open;  // 영업 여부 (true: 영업중, false: 영업종료)

    // 가게에 속한 메뉴 목록 (1:N 관계)
    // cascade=ALL: 가게 저장/삭제 시 메뉴도 함께 처리
    // orphanRemoval=true: 가게에서 메뉴를 제거하면 DB에서도 삭제
    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Menu> menus = new ArrayList<>();

    @Builder
    public Store(String name, String address, String phone, boolean open) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.open = open;
    }

    /** 메뉴 추가 편의 메서드 - 양방향 연관관계 설정 */
    public void addMenu(Menu menu) {
        menus.add(menu);
        menu.setStore(this);  // Menu 쪽에도 Store 참조 설정 (양방향 동기화)
    }
}
