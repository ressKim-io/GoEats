package com.goeats.order.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * OpenFeign 클라이언트: store-service에 HTTP 요청을 보내는 인터페이스.
 *
 * <p>MSA에서 서비스 간 통신의 핵심 패턴입니다. 인터페이스만 정의하면
 * {@code @FeignClient} 어노테이션을 통해 Spring Cloud가 런타임에 구현체를
 * 자동 생성합니다. 개발자는 마치 로컬 메서드를 호출하듯이 사용할 수 있습니다.</p>
 *
 * <p>MSA 서비스 간 통신에서 발생하는 문제들:</p>
 * <ul>
 *   <li>네트워크 지연 (Network Latency) - HTTP 호출이므로 수 ms~수백 ms 소요</li>
 *   <li>장애 전파 위험 - store-service가 다운되면 order-service도 영향을 받음</li>
 *   <li>직렬화/역직렬화 오버헤드 - 객체를 JSON으로 변환하는 비용 발생</li>
 * </ul>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 {@code storeService.getStore(id)}로
 * 같은 JVM 내에서 메서드를 직접 호출합니다. 네트워크 비용이 없고, 실패 가능성도 낮습니다.
 * MSA에서는 HTTP API를 통해 다른 서비스와 통신하므로, Circuit Breaker와 Fallback이 필수입니다.</p>
 *
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
// @FeignClient: "store-service"라는 이름의 서비스에 HTTP 요청을 보냄
// url 속성으로 직접 URL을 지정하거나, 서비스 디스커버리(Eureka 등)를 통해 자동 해석 가능
@FeignClient(name = "store-service", url = "${store-service.url:http://localhost:8082}")
public interface StoreServiceClient {

    // GET /api/stores/{storeId} → store-service의 가게 정보 조회 API를 호출
    @GetMapping("/api/stores/{storeId}")
    StoreResponse getStore(@PathVariable Long storeId);

    // GET /api/stores/{storeId}/menus → 특정 가게의 메뉴 목록을 조회
    @GetMapping("/api/stores/{storeId}/menus")
    List<MenuResponse> getMenusByStore(@PathVariable Long storeId);

    // GET /api/menus/{menuId} → 개별 메뉴 정보를 조회
    @GetMapping("/api/menus/{menuId}")
    MenuResponse getMenu(@PathVariable Long menuId);

    // DTO records for Feign responses
    // Feign 응답을 매핑하기 위한 DTO (record 타입으로 불변 객체 생성)
    // store-service의 응답 JSON이 이 record 구조로 자동 역직렬화됨
    record StoreResponse(Long id, String name, String address, boolean open) {}
    record MenuResponse(Long id, String name, BigDecimal price, boolean available) {}
}
