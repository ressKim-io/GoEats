package com.goeats.store.repository;

import com.goeats.store.entity.Menu;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findByStoreId(Long storeId);

    /**
     * ★ Monolithic: Pessimistic lock at DB level.
     * SELECT ... FOR UPDATE ensures exclusive access within the same DB.
     *
     * Compare with MSA: Redis distributed lock (Redisson) since
     * each service has its own database.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Menu m WHERE m.id = :id")
    Optional<Menu> findByIdWithLock(@Param("id") Long id);
}
