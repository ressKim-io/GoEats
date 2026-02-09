package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Menu;
import com.goeats.store.service.MenuService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 메뉴 API 컨트롤러 - 메뉴 조회 엔드포인트 제공.
 *
 * <p>가게별 메뉴 목록 조회와 개별 메뉴 상세 조회 API를 제공한다.
 * 주문 서비스에서 메뉴 정보 확인 시에도 이 API를 호출한다.</p>
 *
 * <h3>API 목록</h3>
 * <ul>
 *   <li>GET /api/stores/{storeId}/menus - 가게별 판매 가능한 메뉴 목록 조회</li>
 *   <li>GET /api/menus/{menuId} - 개별 메뉴 상세 조회</li>
 * </ul>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 Store/Menu가 같은 프로세스에 있어 메서드 호출로 접근한다.
 * MSA에서는 독립 API로 노출되며, 주문 서비스가 OpenFeign으로 HTTP 호출한다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에는 Rate Limiting이 없어 주문 폭주 시 메뉴 조회 API가 과부하될 수 있다.
 * Traffic 버전에서는 {@code @RateLimiter}로 초당 요청 수를 제한하여
 * Gateway + 서비스 레벨의 이중 방어를 구현한다.</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    /** 가게별 판매 가능한 메뉴 목록 조회 (available=true인 메뉴만) */
    @GetMapping("/stores/{storeId}/menus")
    @RateLimiter(name = "menuApi")  // 서비스 레벨 Rate Limiting
    public ApiResponse<List<Menu>> getMenusByStore(@PathVariable Long storeId) {
        return ApiResponse.ok(menuService.getMenusByStore(storeId));
    }

    /** 개별 메뉴 상세 조회 (주문 시 메뉴 정보 확인용) */
    @GetMapping("/menus/{menuId}")
    @RateLimiter(name = "menuApi")
    public ApiResponse<Menu> getMenu(@PathVariable Long menuId) {
        return ApiResponse.ok(menuService.getMenu(menuId));
    }
}
