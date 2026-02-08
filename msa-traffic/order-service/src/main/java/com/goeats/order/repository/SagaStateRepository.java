package com.goeats.order.repository;

import com.goeats.order.entity.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

    Optional<SagaState> findBySagaId(String sagaId);

    Optional<SagaState> findByOrderId(Long orderId);
}
