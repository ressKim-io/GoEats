package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Menu;
import com.goeats.store.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 메뉴 조회 REST API 컨트롤러.
 *
 * <p>MSA에서 이 API는 다른 서비스(주로 order-service)가 OpenFeign으로 호출합니다.
 * 주문 생성 시 order-service가 메뉴 가격을 확인하기 위해
 * GET /api/menus/{menuId} 를 호출하는 구조입니다.</p>
 *
 * <p>API 목록:
 * <ul>
 *   <li>GET /api/stores/{storeId}/menus : 특정 가게의 메뉴 목록 조회</li>
 *   <li>GET /api/menus/{menuId} : 단일 메뉴 상세 조회 (OpenFeign 호출 대상)</li>
 * </ul>
 * </p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: OrderService가 MenuRepository를 직접 주입받아 DB에서 조회
 *   → 같은 프로세스 내 메서드 호출이므로 네트워크 비용 없음
 * - MSA: order-service가 store-service의 REST API를 OpenFeign으로 호출
 *   → 네트워크 호출이므로 지연 시간 발생, Circuit Breaker로 장애 전파 차단 필요
 *   → @Cacheable로 Redis 캐시 적용하여 DB 조회 및 네트워크 비용 절감</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /**
     * 특정 가게의 메뉴 목록을 조회합니다.
     * 클라이언트(앱)에서 가게 상세 화면에 메뉴를 표시할 때 사용합니다.
     */
    @GetMapping("/stores/{storeId}/menus")
    public ApiResponse<List<Menu>> getMenusByStore(@PathVariable Long storeId) {
        return ApiResponse.ok(menuService.getMenusByStore(storeId));
    }

    /**
     * 단일 메뉴를 조회합니다.
     * order-service가 OpenFeign으로 이 API를 호출하여 메뉴 가격/정보를 확인합니다.
     */
    @GetMapping("/menus/{menuId}")
    public ApiResponse<Menu> getMenu(@PathVariable Long menuId) {
        return ApiResponse.ok(menuService.getMenu(menuId));
    }
}
