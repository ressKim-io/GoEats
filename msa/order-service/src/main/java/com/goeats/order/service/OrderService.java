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
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final StoreServiceClient storeServiceClient;
    private final OrderEventPublisher eventPublisher;

    /**
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
    @Transactional
    @CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")
    public Order createOrder(Long userId, Long storeId, List<Long> menuIds,
                             String paymentMethod, String deliveryAddress) {
        log.info("Creating order: userId={}, storeId={}", userId, storeId);

        // 1. Validate store via OpenFeign (HTTP call to store-service)
        StoreServiceClient.StoreResponse store = storeServiceClient.getStore(storeId);
        if (!store.open()) {
            throw new BusinessException(ErrorCode.STORE_CLOSED);
        }

        // 2. Create order (local transaction only)
        Order order = Order.builder()
                .userId(userId)
                .storeId(storeId)
                .deliveryAddress(deliveryAddress)
                .paymentMethod(paymentMethod)
                .build();

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
        order = orderRepository.save(order);

        // 3. Publish event to Kafka (async - payment will happen in PaymentService)
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

    public Order getOrder(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    public List<Order> getOrdersByUser(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        order.updateStatus(OrderStatus.CANCELLED);
        // ★ MSA: Publish cancellation event for saga compensation
        eventPublisher.publishOrderCancelled(orderId, "User cancelled");
        return order;
    }
}
