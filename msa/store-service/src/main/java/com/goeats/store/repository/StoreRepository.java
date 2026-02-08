package com.goeats.store.repository;

import com.goeats.store.entity.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 가게 엔티티에 대한 JPA Repository 인터페이스.
 *
 * <p>@EntityGraph를 사용하여 가게 조회 시 메뉴를 함께 로딩(Fetch Join)합니다.
 * 이는 N+1 문제를 방지하는 JPA 최적화 기법입니다.</p>
 *
 * <p>N+1 문제란?
 * - 가게 1건 조회(1번) + 메뉴 N건 각각 조회(N번) = 총 N+1번 쿼리 실행
 * - @EntityGraph로 JOIN FETCH하면 1번의 쿼리로 가게+메뉴를 한꺼번에 조회</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic/MSA 모두 @EntityGraph 사용 가능 (JPA 패턴은 동일)
 * - 차이점: MSA에서는 이 결과가 Redis에 캐시되어 여러 인스턴스가 공유
 *   → 한 인스턴스가 DB에서 조회한 결과를 다른 인스턴스도 캐시로 활용</p>
 */
public interface StoreRepository extends JpaRepository<Store, Long> {
    /**
     * 가게 ID로 가게와 메뉴를 함께 조회합니다 (Fetch Join).
     * @EntityGraph(attributePaths = {"menus"})는 LEFT JOIN FETCH를 생성합니다.
     * → SELECT s FROM Store s LEFT JOIN FETCH s.menus WHERE s.id = ?
     */
    @EntityGraph(attributePaths = {"menus"})
    Optional<Store> findWithMenusById(Long id);
}
