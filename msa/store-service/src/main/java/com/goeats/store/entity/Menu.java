package com.goeats.store.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "menus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    private String description;

    private boolean available;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id")
    @Setter(AccessLevel.PACKAGE)
    private Store store;

    @Builder
    public Menu(String name, BigDecimal price, String description, boolean available) {
        this.name = name;
        this.price = price;
        this.description = description;
        this.available = available;
    }
}
