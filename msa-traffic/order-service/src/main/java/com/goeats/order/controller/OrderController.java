package com.goeats.order.controller;

import com.goeats.common.dto.ApiResponse;
import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.order.dto.CreateOrderRequest;
import com.goeats.order.dto.QueueStatusResponse;
import com.goeats.order.entity.Order;
import com.goeats.order.service.OrderQueueService;
import com.goeats.order.service.OrderService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * 주문 REST API 컨트롤러
 *
 * <h3>역할</h3>
 * 주문 생성, 조회, 취소, 대기열 상태 조회 API를 제공한다.
 * Idempotency-Key, RateLimiter, Redis Queue 3가지 트래픽 제어를 적용한다.
 *
 * <h3>트래픽 제어 3단계</h3>
 * <pre>
 * 1단계: Gateway Rate Limiting (Redis Token Bucket) → 전체 요청 속도 제한
 * 2단계: @RateLimiter (Resilience4j) → 서비스 레벨 요청 속도 제한
 * 3단계: Redis Queue (Sorted Set) → 피크타임 주문 대기열 ★ NEW
 * </pre>
 *
 * <h3>★ 주문 대기열 흐름 (피크타임)</h3>
 * <pre>
 * 1. 클라이언트가 POST /api/orders 요청
 * 2. Idempotency-Key 중복 확인
 * 3. isQueueActive() 확인:
 *    - 활성 → 주문 저장 후 대기열에 넣고 QueueStatusResponse 반환
 *    - 비활성 → 즉시 처리 (기존 로직)
 * 4. OrderQueueProcessor(@Scheduled)가 대기열에서 꺼내 처리
 * </pre>
 *
 * <h3>Idempotency-Key 패턴 동작 흐름</h3>
 * <pre>
 * 1. 클라이언트가 POST 요청 시 Idempotency-Key 헤더 포함 (UUID 등 고유값)
 * 2. Redis에서 해당 키 존재 여부 확인 (SETNX - Set If Not Exists)
 * 3. 키가 없으면 → "processing" 값으로 저장 (TTL 24시간) → 주문 처리 진행
 * 4. 키가 있으면 → 중복 요청으로 판단 → 409 Conflict 에러 반환
 * </pre>
 *
 * <h3>★ vs MSA Basic</h3>
 * MSA Basic에는 Idempotency-Key와 대기열이 없어:
 * - 네트워크 재시도 시 주문이 중복 생성될 수 있었다
 * - 피크타임에 시스템 과부하가 발생할 수 있었다
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 단일 DB 트랜잭션 + Pessimistic Lock으로 충분했다.
 * MSA에서는 분산 환경이므로 Redis 기반 멱등성 + 대기열이 필요하다.
 *
 * ★ Traffic MSA: Idempotency-Key + Redis Queue + RateLimiter
 *
 * Flow:
 *   1. Idempotency check (Redis SETNX)
 *   2. Queue check (isQueueActive)
 *   3. If active → enqueue + return QueueStatusResponse
 *   4. If inactive → process immediately
 */
@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderQueueService orderQueueService;
    // Redis를 활용한 Idempotency-Key 저장소 (분산 환경에서도 원자적 동작)
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 주문 생성 API
     *
     * <p>피크타임에는 주문을 대기열에 넣고 QueueStatusResponse를 반환한다.
     * 비피크타임에는 기존처럼 즉시 처리한다.</p>
     *
     * @param idempotencyKey 중복 요청 방지 키 (선택, 클라이언트가 생성한 UUID)
     * @param request        주문 생성 요청 DTO
     *
     * @RateLimiter: Resilience4j 서비스 레벨 Rate Limiting
     * Gateway의 Redis Rate Limiting과 별개로, 서비스 자체에서도 요청 속도를 제한한다.
     * → 이중 방어: Gateway Rate Limit + Service Rate Limit
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimiter(name = "orderApi")
    public ApiResponse<?> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {

        // ★ Idempotency check via Redis
        if (idempotencyKey != null) {
            String redisKey = "idempotency:order:" + idempotencyKey;
            Boolean isNew = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
            if (Boolean.FALSE.equals(isNew)) {
                throw new BusinessException(ErrorCode.DUPLICATE_REQUEST,
                        "Duplicate order request detected for key: " + idempotencyKey);
            }
        }

        // ★ Queue check: if peak-time, enqueue instead of immediate processing
        if (orderQueueService.isQueueActive()) {
            // Save order first (PAYMENT_PENDING status), then enqueue
            Order order = orderService.createOrder(
                    request.userId(), request.storeId(), request.menuIds(),
                    request.paymentMethod(), request.deliveryAddress());

            QueueStatusResponse queueStatus = orderQueueService.enqueue(order.getId());
            log.info("Order queued during peak time: orderId={}, position={}",
                    order.getId(), queueStatus.position());
            return ApiResponse.ok(queueStatus);
        }

        // Normal flow: process immediately
        orderQueueService.incrementActiveOrders();
        Order order = orderService.createOrder(
                request.userId(), request.storeId(), request.menuIds(),
                request.paymentMethod(), request.deliveryAddress());
        return ApiResponse.ok(order);
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

    /**
     * 주문 대기열 상태 조회 API
     *
     * <p>대기열에 들어간 주문의 현재 순번과 예상 대기 시간을 반환한다.</p>
     *
     * @param orderId 대기열에서 조회할 주문 ID
     * @return 대기열 상태 (순번, 예상 대기 시간, 전체 대기열 크기)
     */
    @GetMapping("/queue/status")
    public ApiResponse<QueueStatusResponse> getQueueStatus(@RequestParam Long orderId) {
        return ApiResponse.ok(orderQueueService.getQueueStatus(orderId));
    }
}
