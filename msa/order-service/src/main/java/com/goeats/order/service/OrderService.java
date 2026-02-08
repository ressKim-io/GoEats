package com.goeats.order.service;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.order.client.StoreServiceClient;
import com.goeats.order.entity.Order;
import com.goeats.order.entity.OrderItem;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문 서비스의 핵심 비즈니스 로직.
 *
 * <p>MSA에서 주문 생성 흐름은 다음과 같습니다:</p>
 * <ol>
 *   <li>OpenFeign으로 store-service에서 가게/메뉴 정보를 조회 (HTTP 통신)</li>
 *   <li>주문을 order-service의 로컬 DB에 저장 (로컬 트랜잭션)</li>
 *   <li>Kafka에 OrderCreatedEvent를 발행 (비동기 이벤트)</li>
 *   <li>PaymentService가 이벤트를 수신하여 결제를 처리</li>
 *   <li>결제 결과 이벤트를 다시 수신하여 주문 상태를 업데이트</li>
 * </ol>
 *
 * <p>핵심 MSA 패턴:</p>
 * <ul>
 *   <li>{@code @CircuitBreaker} - store-service 장애 시 즉시 실패하여 연쇄 장애(Cascading Failure) 방지.
 *       일정 횟수 이상 실패하면 회로가 열려서 요청을 보내지 않고 바로 Fallback 실행</li>
 *   <li>Saga 패턴 - 분산 트랜잭션의 대안. 각 서비스가 로컬 트랜잭션을 수행하고,
 *       실패 시 보상 트랜잭션(Compensation)으로 롤백을 대체</li>
 * </ul>
 *
 * <p>★ Monolithic과의 차이: Monolithic에서는 하나의 @Transactional로
 * 주문 생성 + 결제 + 배달 요청이 모두 동기적으로 처리됩니다.
 * 실패 시 DB 롤백 한 번이면 되지만, MSA에서는 이미 커밋된 데이터를
 * 보상 트랜잭션으로 되돌려야 합니다.</p>
 *
 * ★★★ CORE COMPARISON POINT ★★★
 *
 * MSA OrderService: Saga pattern with Kafka events.
 *
 * Key characteristics (vs Monolithic):
 * 1. TRANSACTION: Only saves order locally, publishes Kafka event for payment
 *    → No single @Transactional across services (saga compensation instead)
 * 2. SERVICE CALLS: OpenFeign HTTP client with Circuit Breaker
 *    → Network latency, need resilience patterns
 * 3. DATA ACCESS: Only orderId stored, no FK to other service's tables
 *    → Each service owns its database
 * 4. ERROR HANDLING: Circuit Breaker (Resilience4j) + Fallback
 *    → Fault isolation per service
 * 5. EVENTS: Kafka (distributed, persistent, replay-able)
 *    → Asynchronous, at-least-once delivery
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // 기본적으로 읽기 전용 트랜잭션 (성능 최적화)
public class OrderService {

    private final OrderRepository orderRepository;
    private final StoreServiceClient storeServiceClient; // OpenFeign HTTP 클라이언트
    private final OrderEventPublisher eventPublisher;     // Kafka 이벤트 발행기

    /**
     * 주문 생성 메서드.
     *
     * <p>MSA에서 주문 생성은 로컬 트랜잭션만 수행합니다.
     * 결제는 Kafka 이벤트를 통해 비동기적으로 처리됩니다.</p>
     *
     * <p>흐름:</p>
     * <ol>
     *   <li>OpenFeign으로 store-service에 가게 검증 요청 (Circuit Breaker 보호)</li>
     *   <li>주문을 order_db에 저장 (로컬 트랜잭션)</li>
     *   <li>OrderCreatedEvent를 Kafka에 발행</li>
     *   <li>PaymentService가 이벤트를 수신 → 결제 처리 → 결과 이벤트 발행</li>
     *   <li>이 서비스가 결제 결과 이벤트를 수신 → 주문 상태 업데이트</li>
     * </ol>
     *
     * ★ MSA: Order creation is LOCAL transaction only.
     * Payment happens asynchronously via Kafka event.
     *
     * Flow:
     * 1. Validate store via OpenFeign (Circuit Breaker protected)
     * 2. Save order to order_db (local transaction)
     * 3. Publish OrderCreatedEvent to Kafka
     * 4. PaymentService listens → processes payment → publishes result
     * 5. This service listens for payment result → updates order status
     *
     * Compare with Monolithic:
     * 1. storeService.getStoreWithMenus(storeId) - direct call
     * 2-4. orderRepo.save() + paymentService.processPayment() + deliveryService.createDelivery()
     *      ALL in single @Transactional
     */
    @Transactional // 쓰기 트랜잭션으로 전환 (readOnly = false)
    @CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")
    // ↑ Circuit Breaker: store-service 호출 실패 시 createOrderFallback 메서드 실행
    public Order createOrder(Long userId, Long storeId, List<Long> menuIds,
                             String paymentMethod, String deliveryAddress) {
        log.info("Creating order: userId={}, storeId={}", userId, storeId);

        // 1. Validate store via OpenFeign (HTTP call to store-service)
        // 1단계: OpenFeign을 통해 store-service에 HTTP GET 요청
        // Monolithic에서는 storeRepository.findById(storeId)로 같은 DB에서 직접 조회
        StoreServiceClient.StoreResponse store = storeServiceClient.getStore(storeId);
        if (!store.open()) {
            throw new BusinessException(ErrorCode.STORE_CLOSED);
        }

        // 2. Create order (local transaction only)
        // 2단계: 주문 엔티티 생성 (아직 DB에 저장되지 않은 상태)
        Order order = Order.builder()
                .userId(userId)
                .storeId(storeId)
                .deliveryAddress(deliveryAddress)
                .paymentMethod(paymentMethod)
                .build();

        // 각 메뉴 ID에 대해 store-service에서 메뉴 정보를 HTTP로 조회
        // Monolithic에서는 menuRepository.findById(menuId)로 직접 조회
        for (Long menuId : menuIds) {
            StoreServiceClient.MenuResponse menu = storeServiceClient.getMenu(menuId);
            OrderItem item = OrderItem.builder()
                    .menuId(menuId)
                    .quantity(1)
                    .price(menu.price())
                    .build();
            order.addItem(item);
        }

        order.updateStatus(OrderStatus.PAYMENT_PENDING);
        order = orderRepository.save(order); // 로컬 DB에 주문 저장 (여기서 트랜잭션 커밋)

        // 3. Publish event to Kafka (async - payment will happen in PaymentService)
        // 3단계: Kafka에 이벤트 발행 → PaymentService가 비동기로 결제 처리
        // 중요: 이 시점에서 DB 트랜잭션은 이미 커밋됨. Kafka 발행이 실패하면
        // 주문은 PAYMENT_PENDING 상태로 남게 됨 (데이터 불일치 가능)
        // → msa-traffic에서는 Outbox 패턴으로 이 문제를 해결
        List<OrderCreatedEvent.OrderItemDto> itemDtos = order.getItems().stream()
                .map(item -> new OrderCreatedEvent.OrderItemDto(
                        item.getMenuId(), item.getQuantity(), item.getPrice()))
                .toList();

        eventPublisher.publishOrderCreated(new OrderCreatedEvent(
                order.getId(), userId, storeId, itemDtos,
                order.getTotalAmount(), deliveryAddress, paymentMethod));

        log.info("Order created and event published: orderId={}", order.getId());
        return order;
    }

    /**
     * Circuit Breaker 폴백 메서드: store-service가 다운되었을 때 실행됩니다.
     *
     * <p>연쇄 장애(Cascading Failure)를 방지하기 위해, store-service 호출 실패 시
     * 의미 있는 에러 메시지를 반환합니다. Circuit Breaker가 열린(OPEN) 상태에서는
     * 실제 HTTP 요청을 보내지 않고 바로 이 메서드가 실행됩니다.</p>
     *
     * ★ MSA: Circuit Breaker fallback when store-service is down.
     * Returns a meaningful error instead of cascading failure.
     *
     * Compare with Monolithic: Simple try-catch, no circuit breaker needed
     * since all services are in the same JVM.
     */
    private Order createOrderFallback(Long userId, Long storeId, List<Long> menuIds,
                                      String paymentMethod, String deliveryAddress,
                                      Throwable t) {
        log.warn("Store service unavailable, order creation failed: {}", t.getMessage());
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service is temporarily unavailable. Please try again later.");
    }

    // 주문 단건 조회 - @EntityGraph로 items를 함께 로딩 (N+1 방지)
    public Order getOrder(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    // 사용자별 주문 목록 조회
    public List<Order> getOrdersByUser(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    // 주문 취소 + Saga 보상 이벤트 발행
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        // 이미 배달 완료된 주문은 취소 불가
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        order.updateStatus(OrderStatus.CANCELLED);
        // ★ MSA: Publish cancellation event for saga compensation
        // ★ Saga 보상 트랜잭션: 취소 이벤트를 발행하여 PaymentService/DeliveryService에
        // 이미 진행된 결제/배달을 취소하도록 알림
        // Monolithic에서는 같은 트랜잭션에서 payment.cancel(), delivery.cancel()을 직접 호출
        eventPublisher.publishOrderCancelled(orderId, "User cancelled");
        return order;
    }
}
