package com.goeats.delivery.repository;

import com.goeats.delivery.entity.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    Optional<Delivery> findByOrderId(Long orderId);

    /**
     * ★ Fencing Token UPDATE: only updates if the provided fencing token
     * is greater than the stored one. Prevents stale lock holders from
     * overwriting newer data.
     *
     * Returns 1 if updated (token is newer), 0 if skipped (token is stale).
     */
    @Modifying
    @Query("UPDATE Delivery d SET d.riderName = :riderName, d.riderPhone = :riderPhone, " +
            "d.status = com.goeats.delivery.entity.DeliveryStatus.RIDER_ASSIGNED, " +
            "d.lastFencingToken = :fencingToken " +
            "WHERE d.id = :id AND (d.lastFencingToken IS NULL OR d.lastFencingToken < :fencingToken)")
    int updateWithFencingToken(@Param("id") Long id,
                               @Param("riderName") String riderName,
                               @Param("riderPhone") String riderPhone,
                               @Param("fencingToken") Long fencingToken);
}
