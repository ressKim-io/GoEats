package com.goeats.order.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.order.dto.CreateOrderRequest;
import com.goeats.order.entity.Order;
import com.goeats.order.service.OrderService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

/**
 * 주문 REST API 컨트롤러
 *
 * <h3>역할</h3>
 * 주문 생성, 조회, 취소 API를 제공한다.
 * Idempotency-Key 헤더를 통한 중복 요청 방지와 Resilience4j RateLimiter를 적용한다.
 *
 * <h3>Idempotency-Key 패턴 동작 흐름</h3>
 * <pre>
 * 1. 클라이언트가 POST 요청 시 Idempotency-Key 헤더 포함 (UUID 등 고유값)
 * 2. Redis에서 해당 키 존재 여부 확인 (SETNX - Set If Not Exists)
 * 3. 키가 없으면 → "processing" 값으로 저장 (TTL 24시간) → 주문 처리 진행
 * 4. 키가 있으면 → 중복 요청으로 판단 → 409 Conflict 에러 반환
 * </pre>
 *
 * <h3>왜 Idempotency-Key가 필요한가?</h3>
 * 네트워크 타임아웃 시 클라이언트가 동일 요청을 재전송할 수 있다.
 * Idempotency-Key 없이는 같은 주문이 중복 생성될 위험이 있다.
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 Idempotency-Key가 없어, 네트워크 재시도 시 주문이 중복 생성될 수 있었다.
 * MSA-Traffic에서는 Redis SETNX로 원자적 중복 검사를 수행하여 API 멱등성을 보장한다.
 * 또한 @RateLimiter로 서비스 레벨의 요청 속도 제한을 추가로 적용한다.
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 단일 DB 트랜잭션으로 중복 방지가 가능했다 (Unique 제약 조건 등).
 * MSA에서는 분산 환경이므로 Redis와 같은 외부 저장소를 활용한 멱등성 보장이 필요하다.
 *
 * ★ Traffic MSA: Idempotency-Key header for duplicate request prevention
 *
 * vs Basic MSA: No idempotency → duplicate orders on network retry
 *
 * Flow:
 *   1. Client sends POST with Idempotency-Key header
 *   2. Check Redis if key already used
 *   3. If used → return DUPLICATE_REQUEST error (409)
 *   4. If new → process + store key in Redis with TTL
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    // Redis를 활용한 Idempotency-Key 저장소 (분산 환경에서도 원자적 동작)
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 주문 생성 API
     *
     * @param idempotencyKey 중복 요청 방지 키 (선택, 클라이언트가 생성한 UUID)
     * @param userId         주문자 ID (Gateway에서 X-User-Id로 전파됨)
     * @param storeId        가게 ID
     * @param menuIds        주문할 메뉴 ID 목록
     * @param paymentMethod  결제 수단 (CARD, CASH 등)
     * @param deliveryAddress 배달 주소
     *
     * @RateLimiter: Resilience4j 서비스 레벨 Rate Limiting
     * Gateway의 Redis Rate Limiting과 별개로, 서비스 자체에서도 요청 속도를 제한한다.
     * → 이중 방어: Gateway Rate Limit + Service Rate Limit
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimiter(name = "orderApi")
    public ApiResponse<Order> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        // ★ Idempotency check via Redis
        // ★ Redis SETNX로 원자적 중복 확인: 키가 이미 존재하면 중복 요청
        if (idempotencyKey != null) {
            String redisKey = "idempotency:order:" + idempotencyKey;
            // setIfAbsent = Redis SETNX 명령어: 원자적으로 "키가 없으면 저장"
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(isNew)) {
                // 키가 이미 존재 → 동일한 Idempotency-Key로 이미 요청이 처리됨
                throw new BusinessException(ErrorCode.DUPLICATE_REQUEST,
                        "Duplicate order request detected for key: " + idempotencyKey);
            }
        }

        return ApiResponse.ok(orderService.createOrder(
                request.userId(), request.storeId(), request.menuIds(),
                request.paymentMethod(), request.deliveryAddress()));
    }

    /** 주문 단건 조회 (Fetch Join으로 OrderItem까지 한 번에 로딩) */
    @GetMapping("/{id}")
    public ApiResponse<Order> getOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.getOrder(id));
    }

    /** 주문 취소 (SagaState도 함께 FAILED로 전환) */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Order> cancelOrder(@PathVariable Long id) {
        return ApiResponse.ok(orderService.cancelOrder(id));
    }
}
