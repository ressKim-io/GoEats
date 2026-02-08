package com.goeats.store.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.store.entity.Menu;
import com.goeats.store.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 메뉴 비즈니스 로직을 담당하는 서비스.
 *
 * <p>@Cacheable로 Redis 분산 캐시를 적용하여 메뉴 조회 성능을 최적화합니다.
 * 메뉴는 자주 조회되지만 변경은 드물기 때문에 캐시 적중률이 높습니다.</p>
 *
 * <p>캐시 동작 방식:
 * <ul>
 *   <li>getMenu(1L) 첫 번째 호출 → DB 조회 → Redis에 캐시 저장 → 결과 반환</li>
 *   <li>getMenu(1L) 두 번째 호출 → Redis 캐시에서 즉시 반환 (DB 조회 안 함)</li>
 *   <li>메뉴 변경 시 → @CacheEvict로 캐시 무효화 필요</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: @Cacheable + Caffeine(로컬 캐시)
 *   → 캐시가 JVM 메모리에 저장, 서버 재시작 시 캐시 소멸
 *   → 서버가 여러 대이면 각 서버마다 별도 캐시 (데이터 불일치 가능)
 * - MSA: @Cacheable + Redis(분산 캐시)
 *   → 캐시가 Redis 서버에 저장, 서비스 재시작해도 캐시 유지
 *   → 모든 인스턴스가 같은 Redis를 바라보므로 캐시 일관성 보장
 *   → order-service가 OpenFeign으로 호출해도 캐시 덕분에 빠른 응답</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;

    /**
     * 메뉴 ID로 단일 메뉴를 조회합니다.
     * @Cacheable: Redis "menus" 캐시에서 먼저 찾고, 없으면 DB 조회 후 캐시 저장
     */
    @Cacheable(value = "menus", key = "#menuId")
    public Menu getMenu(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }

    /**
     * 가게 ID로 해당 가게의 메뉴 목록을 조회합니다.
     * @Cacheable: Redis "storeMenus" 캐시에서 먼저 찾고, 없으면 DB 조회 후 캐시 저장
     */
    @Cacheable(value = "storeMenus", key = "#storeId")
    public List<Menu> getMenusByStore(Long storeId) {
        return menuRepository.findByStoreId(storeId);
    }
}
