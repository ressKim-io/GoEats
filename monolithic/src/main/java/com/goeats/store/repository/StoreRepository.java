package com.goeats.store.repository;

import com.goeats.store.entity.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /**
     * ★ Monolithic: @EntityGraph to fetch menus with store in one query (prevent N+1).
     *
     * Compare with MSA: Store service returns menus directly from its own DB,
     * no cross-service JOIN needed.
     */
    @EntityGraph(attributePaths = {"menus"})
    Optional<Store> findWithMenusById(Long id);
}
