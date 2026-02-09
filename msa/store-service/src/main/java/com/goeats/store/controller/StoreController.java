package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Store;
import com.goeats.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 가게 조회 REST API 컨트롤러.
 *
 * <p>가게 정보를 조회하는 API를 제공합니다.
 * @EntityGraph를 사용하여 메뉴 정보를 함께 로딩(Fetch Join)하여
 * N+1 문제를 방지합니다.</p>
 *
 * <p>★ Monolithic과의 차이:
 * - Monolithic: 하나의 애플리케이션에서 가게/메뉴/주문 모두 처리
 * - MSA: 가게 서비스가 독립적으로 운영, 다른 서비스는 API로 접근
 *   → 가게 정보 변경이 다른 서비스에 영향을 주지 않음 (독립 배포)</p>
 */
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    /**
     * 가게 상세 정보를 메뉴 목록과 함께 조회합니다.
     * @EntityGraph를 통해 메뉴를 한 번의 쿼리로 함께 로딩합니다(N+1 방지).
     */
    @GetMapping("/{id}")
    public ApiResponse<Store> getStore(@PathVariable Long id) {
        return ApiResponse.ok(storeService.getStoreWithMenus(id));
    }
}
