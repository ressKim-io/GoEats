package com.goeats.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.math.BigDecimal;
import java.util.List;

/**
 * Store 서비스 Feign 클라이언트 - 서비스 간 동기 HTTP 통신
 *
 * <h3>역할</h3>
 * Order 서비스에서 Store 서비스의 REST API를 호출하기 위한 선언적 HTTP 클라이언트.
 * 인터페이스만 정의하면 Spring Cloud OpenFeign이 프록시 구현체를 자동 생성한다.
 *
 * <h3>동작 원리</h3>
 * <pre>
 * 1. @FeignClient 어노테이션으로 대상 서비스 이름과 URL 지정
 * 2. 메서드 시그니처 + @GetMapping으로 REST API 엔드포인트 매핑
 * 3. 호출 시 OpenFeign이 HTTP 요청으로 변환하여 전송
 * 4. 응답 JSON을 record 객체로 자동 역직렬화
 * </pre>
 *
 * <h3>Resilience4j 통합</h3>
 * 이 Feign 클라이언트의 호출은 OrderService에서 @Retry, @CircuitBreaker,
 * @Bulkhead 어노테이션으로 보호된다.
 * - 일시적 네트워크 오류 → @Retry로 자동 재시도
 * - Store 서비스 장애 → @CircuitBreaker로 빠른 실패 + Fallback
 * - 동시 요청 과다 → @Bulkhead로 스레드 풀 격리
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에서도 Feign 클라이언트를 사용했지만, @CircuitBreaker만 적용했다.
 * MSA-Traffic에서는 FeignConfig로 타임아웃을 세밀하게 설정하고,
 * Resilience4j 5대 패턴(Retry, CB, Bulkhead, RateLimiter, TimeLimiter)을 조합한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 StoreRepository.findById()로 DB에서 직접 조회했다 (메서드 호출).
 * MSA에서는 Store가 독립 서비스이므로 네트워크를 통해 HTTP로 조회해야 한다.
 * 네트워크 호출은 실패할 수 있으므로 Resilience 패턴이 필수적이다.
 *
 * @see com.goeats.order.config.FeignConfig 타임아웃 설정
 * @see com.goeats.order.service.OrderService Resilience4j 어노테이션 적용
 */
@FeignClient(name = "store-service", url = "${store-service.url:http://localhost:8082}")
public interface StoreServiceClient {

    /** 가게 정보 조회 - 가게 존재 여부 및 영업 상태 확인용 */
    @GetMapping("/api/stores/{storeId}")
    StoreResponse getStore(@PathVariable Long storeId);

    /** 가게의 전체 메뉴 목록 조회 */
    @GetMapping("/api/stores/{storeId}/menus")
    List<MenuResponse> getMenusByStore(@PathVariable Long storeId);

    /** 개별 메뉴 조회 - 주문 시 메뉴 가격 확인용 */
    @GetMapping("/api/menus/{menuId}")
    MenuResponse getMenu(@PathVariable Long menuId);

    /** 가게 응답 DTO (record: 불변 데이터 객체, Java 16+) */
    record StoreResponse(Long id, String name, String address, boolean open) {}

    /** 메뉴 응답 DTO */
    record MenuResponse(Long id, String name, BigDecimal price, boolean available) {}
}
