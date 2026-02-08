package com.goeats.order.repository;

import com.goeats.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

/**
 * 주문 저장소 - Order 엔티티의 데이터 액세스 계층
 *
 * <h3>역할</h3>
 * Order 엔티티의 CRUD 및 커스텀 쿼리를 제공한다.
 *
 * <h3>findWithItemsById - Fetch Join 쿼리</h3>
 * <pre>
 * 일반 findById()는 Order만 조회하고, items는 Lazy Loading으로 별도 쿼리가 실행된다.
 * findWithItemsById()는 LEFT JOIN FETCH로 Order + OrderItem을 한 번의 쿼리로 조회한다.
 * → N+1 문제 방지 (주문 1건 + 주문항목 N건 = 1+N번 쿼리 → 1번 쿼리)
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서도 동일한 패턴을 사용했다.
 * Fetch Join은 아키텍처와 무관한 JPA 성능 최적화 패턴이다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 Order, Store, Payment가 같은 DB에 있어
 * 여러 도메인 간 JOIN이 가능했다 (예: Order JOIN Store).
 * MSA에서는 자기 서비스의 DB만 접근 가능하므로,
 * Order 서비스는 Order + OrderItem만 JOIN할 수 있다.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 주문 + 주문항목을 Fetch Join으로 한 번에 조회
     *
     * LEFT JOIN FETCH: OrderItem이 없는 주문도 조회 가능
     * (INNER JOIN FETCH를 사용하면 항목 없는 주문은 누락됨)
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findWithItemsById(Long id);
}
