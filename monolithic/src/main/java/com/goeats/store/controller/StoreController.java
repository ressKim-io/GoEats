package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Menu;
import com.goeats.store.entity.Store;
import com.goeats.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/{id}")
    public ApiResponse<Store> getStore(@PathVariable Long id) {
        return ApiResponse.ok(storeService.getStoreWithMenus(id));
    }

    @GetMapping("/{storeId}/menus")
    public ApiResponse<List<Menu>> getMenus(@PathVariable Long storeId) {
        return ApiResponse.ok(storeService.getMenusByStore(storeId));
    }
}
