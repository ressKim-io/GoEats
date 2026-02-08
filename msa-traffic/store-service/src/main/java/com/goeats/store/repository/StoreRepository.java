package com.goeats.store.repository;

import com.goeats.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    @Query("SELECT s FROM Store s LEFT JOIN FETCH s.menus WHERE s.id = :id")
    Optional<Store> findWithMenusById(Long id);

    List<Store> findByOpenTrue();
}
