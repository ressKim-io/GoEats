package com.goeats.store.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.store.entity.Store;
import com.goeats.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stores")
@RequiredArgsConstructor
public class StoreController {

    private final StoreService storeService;

    @GetMapping("/{id}")
    public ApiResponse<Store> getStore(@PathVariable Long id) {
        return ApiResponse.ok(storeService.getStoreWithMenus(id));
    }
}
