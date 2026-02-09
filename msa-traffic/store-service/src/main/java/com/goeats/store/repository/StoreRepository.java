package com.goeats.store.repository;

import com.goeats.store.entity.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 가게(Store) 레포지토리 - 가게 데이터 접근 계층.
 *
 * <p>기본 CRUD 외에 메뉴를 포함한 가게 조회(Fetch Join)와
 * 영업중 가게 목록 조회 기능을 제공한다.</p>
 *
 * <h3>쿼리 메서드</h3>
 * <ul>
 *   <li>{@code findWithMenusById}: JPQL Fetch Join으로 가게 + 메뉴를 한 번의 쿼리로 조회 (N+1 방지)</li>
 *   <li>{@code findByOpenTrue}: 영업중인 가게 목록 조회 (메인 화면, Cache Warming 용)</li>
 * </ul>
 *
 * <h3>★ Fetch Join vs N+1 문제</h3>
 * <p>{@code findWithMenusById}는 LEFT JOIN FETCH로 Store와 Menu를 한 번에 가져온다.
 * 이것이 없으면 Store 조회 후 menus 접근 시 추가 쿼리가 발생한다 (N+1 문제).</p>
 * <pre>
 * [N+1 문제]         [Fetch Join 해결]
 * SELECT * FROM stores WHERE id=1   → SELECT s.*, m.* FROM stores s
 * SELECT * FROM menus WHERE store_id=1     LEFT JOIN menus m ON s.id = m.store_id
 * (쿼리 2번)                                WHERE s.id = 1  (쿼리 1번!)
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서도 동일한 Fetch Join을 사용하지만, OSIV(Open Session In View)가 활성화되어
 * Lazy Loading이 자연스럽게 동작한다. MSA에서는 API 응답 시 세션이 닫히므로
 * 반드시 Fetch Join으로 필요한 데이터를 미리 로딩해야 한다.</p>
 */
public interface StoreRepository extends JpaRepository<Store, Long> {

    /** 가게 + 메뉴를 Fetch Join으로 한 번에 조회 (N+1 문제 방지) */
    @Query("SELECT s FROM Store s LEFT JOIN FETCH s.menus WHERE s.id = :id")
    Optional<Store> findWithMenusById(Long id);

    /** 영업중인 가게 목록 조회 (메인 화면 표시용, Cache Warming에서도 사용) */
    List<Store> findByOpenTrue();
}
