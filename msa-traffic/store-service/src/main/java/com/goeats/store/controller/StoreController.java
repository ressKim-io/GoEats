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

    @GetMapping("/{id}")
    @RateLimiter(name = "storeApi")
    public ApiResponse<Store> getStore(@PathVariable Long id) {
        return ApiResponse.ok(storeService.getStoreWithMenus(id));
    }

    @GetMapping
    @RateLimiter(name = "storeApi")
    public ApiResponse<List<Store>> getOpenStores() {
        return ApiResponse.ok(storeService.getOpenStores());
    }
}
