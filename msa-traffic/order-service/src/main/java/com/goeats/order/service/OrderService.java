package com.goeats.order.service;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.common.outbox.OutboxService;
import com.goeats.order.client.StoreServiceClient;
import com.goeats.order.entity.*;
import com.goeats.order.event.OrderStatusPublisher;
import com.goeats.order.repository.OrderRepository;
import com.goeats.order.repository.SagaStateRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 주문 서비스 - MSA-Traffic의 핵심 비즈니스 로직 (프로덕션급 패턴 적용)
 *
 * <h3>역할</h3>
 * 주문 생성/조회/취소를 처리하며, 분산 트랜잭션(Saga)의 시작점 역할을 한다.
 * Transactional Outbox 패턴으로 이벤트 발행의 원자성을 보장한다.
 *
 * <h3>MSA Basic에서 달라진 점 3가지</h3>
 * <ol>
 *   <li><b>이벤트 발행:</b> kafkaTemplate.send() → outboxService.saveEvent()
 *       <br>MSA Basic: 주문 저장(DB) + Kafka 발행이 별도 → Kafka 실패 시 이벤트 유실
 *       <br>MSA-Traffic: 주문 + Outbox 이벤트를 같은 트랜잭션에 저장 → 원자성 보장</li>
 *   <li><b>Resilience4j:</b> @CircuitBreaker만 → @Retry + @CircuitBreaker + @Bulkhead 조합
 *       <br>MSA Basic: Circuit Breaker만 적용 (재시도 없음, 스레드 격리 없음)
 *       <br>MSA-Traffic: 3중 보호 (일시 오류 재시도 + 장애 차단 + 동시 요청 제한)</li>
 *   <li><b>Saga 추적:</b> 없음 → SagaState 생명주기 관리
 *       <br>MSA Basic: Saga 상태를 기록하지 않아 장애 디버깅 불가
 *       <br>MSA-Traffic: DB에 Saga 상태를 기록하여 어디서 실패했는지 추적</li>
 * </ol>
 *
 * <h3>Resilience4j 어노테이션 실행 순서 (바깥 → 안쪽)</h3>
 * <pre>
 * @Retry (가장 바깥: 실패 시 전체를 재시도)
 *   └─ @CircuitBreaker (중간: 연속 실패 감지 시 빠른 실패)
 *       └─ @Bulkhead (가장 안쪽: 동시 실행 수 제한)
 *           └─ 실제 비즈니스 로직 실행
 * </pre>
 *
 * <h3>★ vs Monolithic</h3>
 * Monolithic에서는 하나의 @Transactional로 주문+결제+배달을 동기적으로 처리했다.
 * MSA에서는 각 서비스가 독립적이므로:
 * - Store 서비스 호출 → OpenFeign (네트워크, 실패 가능)
 * - 결제 요청 → Kafka 이벤트 (비동기, Outbox로 원자성 보장)
 * - 배달 요청 → Kafka 이벤트 (비동기)
 * 네트워크 호출이 포함되므로 Resilience4j 보호가 필수다.
 *
 * ★★★ Traffic MSA Core: OrderService with production-grade patterns
 *
 * Changes from Basic MSA:
 * 1. kafkaTemplate.send() → outboxService.saveEvent() (Transactional Outbox)
 * 2. @CircuitBreaker only → @Retry + @CircuitBreaker + @Bulkhead (full resilience)
 * 3. No saga tracking → SagaState lifecycle management
 *
 * Resilience4j annotation order (outer → inner):
 *   @Retry → @CircuitBreaker → @Bulkhead
 *   Retry wraps CB which wraps Bulkhead
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)  // 기본 읽기 전용 (성능 최적화: 더티 체킹 비활성화)
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaStateRepository;
    private final StoreServiceClient storeServiceClient;   // OpenFeign: Store 서비스 HTTP 클라이언트
    private final OutboxService outboxService;             // Transactional Outbox: 이벤트 저장
    private final OrderStatusPublisher orderStatusPublisher; // ★ Redis Pub/Sub: 실시간 상태 알림

    /**
     * 주문 생성 - 핵심 흐름
     *
     * 하나의 트랜잭션에서 [주문 저장 + SagaState 저장 + Outbox 이벤트 저장]을 수행.
     * → 트랜잭션 커밋 후 Outbox Relay가 이벤트를 Kafka로 발행 (별도 스케줄러)
     *
     * <pre>
     * 실행 흐름:
     * 1. Store 서비스에 가게/메뉴 정보 조회 (OpenFeign, @Retry+@CB+@Bulkhead로 보호)
     * 2. Order 엔티티 생성 + OrderItem 추가
     * 3. SagaState 생성 (분산 트랜잭션 추적 시작)
     * 4. Outbox 이벤트 저장 (같은 트랜잭션에서 원자적으로 저장)
     * 5. [트랜잭션 커밋] → DB에 주문 + SagaState + Outbox 레코드 동시 저장
     * 6. [비동기] Outbox Relay(@Scheduled)가 outbox 테이블을 폴링 → Kafka로 발행
     * </pre>
     *
     * ★ Core flow: Create order + Outbox event + Saga state
     * All in a SINGLE transaction → atomic guarantee
     */
    @Transactional
    @Retry(name = "storeService")                                          // 바깥: Store 호출 실패 시 재시도
    @CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")  // 중간: 연속 실패 시 차단
    @Bulkhead(name = "orderCreation")                                      // 안쪽: 동시 주문 수 제한
    public Order createOrder(Long userId, Long storeId, List<Long> menuIds,
                             String paymentMethod, String deliveryAddress) {
        log.info("Creating order: userId={}, storeId={}", userId, storeId);

        // 1. OpenFeign → Store validation (protected by Retry + CB + Bulkhead)
        // 1단계: Store 서비스에 가게 정보 조회 (Resilience4j 3중 보호)
        StoreServiceClient.StoreResponse store = storeServiceClient.getStore(storeId);
        if (!store.open()) {
            throw new BusinessException(ErrorCode.STORE_CLOSED);
        }

        // 2. Build Order entity
        // 2단계: 주문 엔티티 구성
        Order order = Order.builder()
                .userId(userId)
                .storeId(storeId)
                .deliveryAddress(deliveryAddress)
                .paymentMethod(paymentMethod)
                .build();

        // 각 메뉴에 대해 Store 서비스에서 가격 조회 후 주문 항목 추가
        for (Long menuId : menuIds) {
            StoreServiceClient.MenuResponse menu = storeServiceClient.getMenu(menuId);
            OrderItem item = OrderItem.builder()
                    .menuId(menuId)
                    .quantity(1)
                    .price(menu.price())  // 주문 시점의 가격을 스냅샷으로 저장
                    .build();
            order.addItem(item);
        }

        order.updateStatus(OrderStatus.PAYMENT_PENDING);  // 결제 대기 상태로 변경
        order = orderRepository.save(order);

        // 3. ★ Saga State: track distributed transaction lifecycle
        // 3단계: Saga 상태 생성 - 분산 트랜잭션 추적 시작
        String sagaId = UUID.randomUUID().toString();
        SagaState sagaState = SagaState.builder()
                .sagaId(sagaId)
                .sagaType("CREATE_ORDER")
                .orderId(order.getId())
                .build();
        sagaStateRepository.save(sagaState);

        // 4. ★ Transactional Outbox: save event in SAME transaction as order
        //    (vs Basic MSA: kafkaTemplate.send() which can fail after commit)
        // 4단계: Transactional Outbox로 이벤트 저장
        // ★ 핵심: outboxService.saveEvent()는 DB에 저장만 함 (Kafka로 직접 보내지 않음)
        //   → 주문 + 이벤트가 같은 트랜잭션에서 커밋되므로 원자성 보장
        //   → Outbox Relay(@Scheduled)가 나중에 DB를 폴링하여 Kafka로 발행
        List<OrderCreatedEvent.OrderItemDto> itemDtos = order.getItems().stream()
                .map(item -> new OrderCreatedEvent.OrderItemDto(
                        item.getMenuId(), item.getQuantity(), item.getPrice()))
                .toList();

        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(), userId, storeId, itemDtos,
                order.getTotalAmount(), deliveryAddress, paymentMethod);

        outboxService.saveEvent("Order", order.getId().toString(),
                "OrderCreated", event);

        // 5. ★ Redis Pub/Sub: 실시간 주문 상태 알림 발행
        orderStatusPublisher.publish(order.getId(), OrderStatus.PAYMENT_PENDING.name());

        log.info("Order created with Outbox event: orderId={}, sagaId={}", order.getId(), sagaId);
        return order;
    }

    /**
     * 주문 단건 조회 (Fetch Join으로 주문 항목까지 한 번에 로딩)
     */
    public Order getOrder(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    /**
     * 주문 취소 (사용자가 직접 취소)
     *
     * 배달 완료(DELIVERED) 상태에서는 취소 불가.
     * Saga 상태도 함께 FAILED로 전환한다.
     */
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        order.updateStatus(OrderStatus.CANCELLED);

        // Update saga state
        // Saga 상태를 FAILED로 전환 (사용자 취소)
        sagaStateRepository.findByOrderId(orderId)
                .ifPresent(saga -> saga.fail("User cancelled"));

        // ★ Redis Pub/Sub: 취소 상태 실시간 알림
        orderStatusPublisher.publish(orderId, OrderStatus.CANCELLED.name());

        return order;
    }

    /**
     * 대기열에서 꺼낸 주문을 처리 (OrderQueueProcessor에서 호출).
     *
     * <p>이미 createOrder()에서 주문이 저장되고 Outbox 이벤트도 저장된 상태.
     * 대기열에서 꺼낸 후에는 활성 주문 카운트만 관리하면 된다.</p>
     *
     * <p>Outbox Relay가 이미 이벤트를 브로커로 발행하므로,
     * 여기서는 대기열 처리 완료 로그만 남긴다.</p>
     *
     * ★ Process queued order (called by OrderQueueProcessor)
     *   Order + Outbox event already saved → just manage active count
     */
    @Transactional(readOnly = true)
    public void processQueuedOrder(Long orderId) {
        orderRepository.findById(orderId).ifPresent(order -> {
            log.info("Queued order processing completed: orderId={}, status={}",
                    orderId, order.getStatus());
        });
    }

    /**
     * Circuit Breaker Fallback 메서드
     *
     * Store 서비스 호출이 Circuit Breaker에 의해 차단되었을 때 호출된다.
     * - CircuitBreaker가 Open 상태일 때 (연속 실패로 차단됨)
     * - @Retry 재시도 모두 실패 후
     *
     * Fallback 메서드의 시그니처는 원본 메서드와 동일 + Throwable 파라미터가 추가되어야 한다.
     *
     * ★ Circuit Breaker fallback
     */
    @SuppressWarnings("unused")
    private Order createOrderFallback(Long userId, Long storeId, List<Long> menuIds,
                                      String paymentMethod, String deliveryAddress,
                                      Throwable t) {
        log.warn("Store service unavailable, order creation failed: {}", t.getMessage());
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service is temporarily unavailable. Please try again later.");
    }
}
