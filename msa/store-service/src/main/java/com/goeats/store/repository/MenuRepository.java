package com.goeats.store.repository;

import com.goeats.store.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 메뉴 엔티티에 대한 JPA Repository 인터페이스.
 *
 * <p>가게 서비스 전용 데이터베이스에서 메뉴 데이터에 접근합니다.
 * storeId를 기준으로 해당 가게의 메뉴 목록을 조회합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: Store, Menu, Order가 같은 DB → JOIN/연관관계 자유롭게 사용
 * - MSA: Store/Menu는 store-service DB에만 존재
 *   → order-service에서 메뉴 정보가 필요하면 OpenFeign API 호출로 가져옴
 *   → @Cacheable로 Redis 캐시 적용하여 반복 조회 시 DB 부하 감소</p>
 */
public interface MenuRepository extends JpaRepository<Menu, Long> {
    /**
     * 가게 ID로 해당 가게의 메뉴 목록을 조회합니다.
     * Spring Data JPA가 메서드 이름을 분석하여 자동으로 쿼리를 생성합니다.
     * → SELECT * FROM menu WHERE store_id = ?
     */
    List<Menu> findByStoreId(Long storeId);
}
