package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Menu;
import com.goeats.store.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/stores/{storeId}/menus")
    public ApiResponse<List<Menu>> getMenusByStore(@PathVariable Long storeId) {
        return ApiResponse.ok(menuService.getMenusByStore(storeId));
    }

    @GetMapping("/menus/{menuId}")
    public ApiResponse<Menu> getMenu(@PathVariable Long menuId) {
        return ApiResponse.ok(menuService.getMenu(menuId));
    }
}
