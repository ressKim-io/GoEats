package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Store;
import com.goeats.store.service.StoreService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 가게 API 컨트롤러 - 가게 조회 엔드포인트 제공.
 *
 * <p>가게 상세 조회(메뉴 포함)와 영업중인 가게 목록 조회 API를 제공한다.
 * 사용자 앱의 메인 화면(가게 리스트)과 가게 상세 화면에서 호출된다.</p>
 *
 * <h3>API 목록</h3>
 * <ul>
 *   <li>GET /api/stores/{id} - 가게 상세 조회 (메뉴 목록 포함)</li>
 *   <li>GET /api/stores - 영업중인 가게 목록 조회</li>
 * </ul>
 *
 * <h3>이중 Rate Limiting (Defense-in-Depth)</h3>
 * <pre>
 * [사용자] → [Gateway Rate Limiting] → [서비스 @RateLimiter] → [비즈니스 로직]
 *              (Redis Token Bucket)        (Resilience4j)
 * </pre>
 * <p>Gateway가 전체 트래픽을 1차 제한하고, 서비스 레벨에서 2차 제한한다.
 * Gateway가 우회되더라도(내부 호출 등) 서비스 레벨에서 DB를 보호한다.</p>
 *
 * <h3>★ vs Monolithic</h3>
 * <p>모놀리식에서는 가게 조회가 같은 프로세스 내에서 이루어지므로
 * Rate Limiting 없이 메서드 호출로 처리된다.</p>
 *
 * <h3>★ vs MSA Basic</h3>
 * <p>기본 MSA에는 Rate Limiting이 없어 트래픽 폭주 시 서비스가 과부하될 수 있다.
 * Traffic 버전에서는 Gateway + 서비스 레벨의 이중 방어를 구현한다.</p>
 *
 * ★ Traffic MSA: @RateLimiter at controller level
 *
 * vs Basic MSA: No rate limiting → vulnerable to traffic spikes
 *
 * Rate limit is applied per-service (in addition to Gateway-level limiting).
 * This provides defense-in-depth: even if Gateway limit is bypassed,
 * service-level limit protects the database.
 */
@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    /** 가게 상세 조회 - 메뉴 목록을 포함한 전체 정보 반환 */
    @GetMapping("/{id}")
    @RateLimiter(name = "storeApi")  // 서비스 레벨 Rate Limiting (Gateway와 이중 방어)
    public ApiResponse<Store> getStore(@PathVariable Long id) {
        return ApiResponse.ok(storeService.getStoreWithMenus(id));
    }

    /** 영업중인 가게 목록 조회 - 메인 화면용 */
    @GetMapping
    @RateLimiter(name = "storeApi")
    public ApiResponse<List<Store>> getOpenStores() {
        return ApiResponse.ok(storeService.getOpenStores());
    }
}
