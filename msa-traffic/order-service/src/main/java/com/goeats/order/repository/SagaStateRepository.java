package com.goeats.order.repository;

import com.goeats.order.entity.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Saga 상태 저장소 - SagaState 엔티티의 데이터 액세스 계층
 *
 * <h3>역할</h3>
 * 분산 트랜잭션(Saga)의 상태를 저장/조회한다.
 * 주문 ID 또는 Saga ID로 현재 Saga의 진행 상태를 추적할 수 있다.
 *
 * <h3>주요 조회 메서드</h3>
 * <ul>
 *   <li>findBySagaId: Saga 고유 ID(UUID)로 조회 - Saga 내부에서 사용</li>
 *   <li>findByOrderId: 주문 ID로 Saga 조회 - 이벤트 리스너에서 주로 사용
 *       (이벤트에는 orderId만 포함되므로 orderId로 Saga를 찾아 상태를 업데이트)</li>
 * </ul>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 SagaState 자체가 없었으므로 이 저장소도 존재하지 않았다.
 * MSA-Traffic에서 Saga 상태 추적 패턴을 도입하면서 함께 추가되었다.
 *
 * @see com.goeats.order.entity.SagaState Saga 상태 엔티티
 * @see com.goeats.order.event.PaymentEventListener Saga 상태를 업데이트하는 이벤트 리스너
 */
public interface SagaStateRepository extends JpaRepository<SagaState, Long> {

    /** Saga 고유 ID(UUID)로 조회 */
    Optional<SagaState> findBySagaId(String sagaId);

    /** 주문 ID로 해당 주문의 Saga 조회 (이벤트 리스너에서 주로 사용) */
    Optional<SagaState> findByOrderId(Long orderId);
}
