package com.goeats.order.repository;

import com.goeats.order.entity.Order;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 주문 레포지토리 (Order Repository).
 *
 * <p>Spring Data JPA를 사용한 데이터 접근 계층입니다.</p>
 *
 * <p>{@code @EntityGraph}는 N+1 문제를 방지하기 위해 사용됩니다.
 * 주문(Order)을 조회할 때 주문 항목(OrderItem)을 함께 JOIN FETCH로 로딩하여,
 * items에 접근할 때 추가 쿼리가 발생하지 않도록 합니다.</p>
 *
 * <p>★ Monolithic과의 차이: Repository 패턴 자체는 Monolithic에서도 동일하게 사용됩니다.
 * 차이점은 MSA에서는 주문 서비스의 DB에만 접근할 수 있다는 것입니다.
 * 가게(Store)나 결제(Payment) 데이터는 각 서비스의 DB에 있으므로,
 * JOIN으로 가져올 수 없고 OpenFeign HTTP 호출이나 Kafka 이벤트를 통해 조회해야 합니다.</p>
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
    // @EntityGraph: items 필드를 JOIN FETCH로 즉시 로딩 (N+1 문제 방지)
    // SQL: SELECT o.*, i.* FROM orders o LEFT JOIN order_items i ON o.id = i.order_id WHERE o.id = ?
    @EntityGraph(attributePaths = {"items"})
    Optional<Order> findWithItemsById(Long id);

    // 특정 사용자의 주문 목록 조회
    List<Order> findByUserId(Long userId);
}
