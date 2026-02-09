package com.goeats.store.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stores")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "store_seq")
    @SequenceGenerator(name = "store_seq", sequenceName = "store_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String phone;

    private boolean open;

    /**
     * ★ Monolithic: Direct JPA relationship with @OneToMany.
     * Menus are loaded via JOIN in the same database.
     *
     * Compare with MSA: Store and Menu are in the same service,
     * but other services must call via OpenFeign HTTP API.
     */
    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Menu> menus = new ArrayList<>();

    @Builder
    public Store(String name, String address, String phone, boolean open) {
        this.name = name;
        this.address = address;
        this.phone = phone;
        this.open = open;
    }

    public void addMenu(Menu menu) {
        menus.add(menu);
        menu.setStore(this);
    }
}
