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

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/stores/{storeId}/menus")
    @RateLimiter(name = "menuApi")
    public ApiResponse<List<Menu>> getMenusByStore(@PathVariable Long storeId) {
        return ApiResponse.ok(menuService.getMenusByStore(storeId));
    }

    @GetMapping("/menus/{menuId}")
    @RateLimiter(name = "menuApi")
    public ApiResponse<Menu> getMenu(@PathVariable Long menuId) {
        return ApiResponse.ok(menuService.getMenu(menuId));
    }
}
