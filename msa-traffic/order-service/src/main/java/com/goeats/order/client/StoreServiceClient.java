package com.goeats.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.util.List;

@FeignClient(name = "store-service", url = "${store-service.url:http://localhost:8082}")
public interface StoreServiceClient {

    @GetMapping("/api/stores/{storeId}")
    StoreResponse getStore(@PathVariable Long storeId);

    @GetMapping("/api/stores/{storeId}/menus")
    List<MenuResponse> getMenusByStore(@PathVariable Long storeId);

    @GetMapping("/api/menus/{menuId}")
    MenuResponse getMenu(@PathVariable Long menuId);

    record StoreResponse(Long id, String name, String address, boolean open) {}
    record MenuResponse(Long id, String name, BigDecimal price, boolean available) {}
}
