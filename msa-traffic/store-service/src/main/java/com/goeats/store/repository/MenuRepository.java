package com.goeats.store.repository;

import com.goeats.store.entity.Menu;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 메뉴(Menu) 레포지토리 - 메뉴 데이터 접근 계층.
 *
 * <p>Spring Data JPA의 메서드 이름 기반 쿼리 자동 생성을 활용하여
 * 가게별 메뉴 조회 기능을 제공한다.</p>
 *
 * <h3>쿼리 메서드</h3>
 * <ul>
 *   <li>{@code findByStoreId}: 가게 ID로 전체 메뉴 조회 (관리자용)</li>
 *   <li>{@code findByStoreIdAndAvailableTrue}: 가게 ID로 판매 가능 메뉴만 조회 (고객용)</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Store 엔티티의 menus 필드(JPA 연관관계)를 통해 메뉴를 조회한다.
 * MSA에서는 각 서비스가 독립된 DB를 사용하므로 레포지토리 쿼리로 직접 조회한다.</p>
 *
 * <h3>인덱스 활용</h3>
 * <p>{@code findByStoreIdAndAvailableTrue}는 Menu 엔티티의
 * {@code idx_menu_store_available} 복합 인덱스를 활용하여 빠른 조회가 가능하다.</p>
 */
public interface MenuRepository extends JpaRepository<Menu, Long> {

    /** 가게 ID로 전체 메뉴 조회 (관리자 화면용) */
    List<Menu> findByStoreId(Long storeId);

    /** 가게 ID로 판매 가능한 메뉴만 조회 (고객 앱용, 복합 인덱스 활용) */
    List<Menu> findByStoreIdAndAvailableTrue(Long storeId);
}
