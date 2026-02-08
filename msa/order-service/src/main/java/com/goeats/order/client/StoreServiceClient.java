package com.goeats.order.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * ★★★ MSA: OpenFeign client for inter-service communication.
 *
 * Instead of direct method calls (monolithic), we make HTTP API calls
 * to the Store microservice. This introduces:
 * - Network latency
 * - Need for Circuit Breaker (Resilience4j)
 * - Need for Fallback responses
 * - Serialization/deserialization overhead
 *
 * Compare with Monolithic: storeService.getMenu(menuId) - direct method call.
 */
@FeignClient(name = "store-service", url = "${store-service.url:http://localhost:8082}")
public interface StoreServiceClient {

    @GetMapping("/api/stores/{storeId}")
    StoreResponse getStore(@PathVariable Long storeId);

    @GetMapping("/api/stores/{storeId}/menus")
    List<MenuResponse> getMenusByStore(@PathVariable Long storeId);

    @GetMapping("/api/menus/{menuId}")
    MenuResponse getMenu(@PathVariable Long menuId);

    // DTO records for Feign responses
    record StoreResponse(Long id, String name, String address, boolean open) {}
    record MenuResponse(Long id, String name, BigDecimal price, boolean available) {}
}
