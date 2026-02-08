package com.goeats.store.repository;

import com.goeats.store.entity.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {
    @EntityGraph(attributePaths = {"menus"})
    Optional<Store> findWithMenusById(Long id);
}
