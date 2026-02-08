package com.goeats.order.service;

import com.goeats.common.event.OrderCreatedEvent;
import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.common.outbox.OutboxService;
import com.goeats.order.client.StoreServiceClient;
import com.goeats.order.entity.*;
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
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final SagaStateRepository sagaStateRepository;
    private final StoreServiceClient storeServiceClient;
    private final OutboxService outboxService;

    /**
     * ★ Core flow: Create order + Outbox event + Saga state
     * All in a SINGLE transaction → atomic guarantee
     */
    @Transactional
    @Retry(name = "storeService")
    @CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")
    @Bulkhead(name = "orderCreation")
    public Order createOrder(Long userId, Long storeId, List<Long> menuIds,
                             String paymentMethod, String deliveryAddress) {
        log.info("Creating order: userId={}, storeId={}", userId, storeId);

        // 1. OpenFeign → Store validation (protected by Retry + CB + Bulkhead)
        StoreServiceClient.StoreResponse store = storeServiceClient.getStore(storeId);
        if (!store.open()) {
            throw new BusinessException(ErrorCode.STORE_CLOSED);
        }

        // 2. Build Order entity
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

        // 3. ★ Saga State: track distributed transaction lifecycle
        String sagaId = UUID.randomUUID().toString();
        SagaState sagaState = SagaState.builder()
                .sagaId(sagaId)
                .sagaType("CREATE_ORDER")
                .orderId(order.getId())
                .build();
        sagaStateRepository.save(sagaState);

        // 4. ★ Transactional Outbox: save event in SAME transaction as order
        //    (vs Basic MSA: kafkaTemplate.send() which can fail after commit)
        List<OrderCreatedEvent.OrderItemDto> itemDtos = order.getItems().stream()
                .map(item -> new OrderCreatedEvent.OrderItemDto(
                        item.getMenuId(), item.getQuantity(), item.getPrice()))
                .toList();

        OrderCreatedEvent event = new OrderCreatedEvent(
                order.getId(), userId, storeId, itemDtos,
                order.getTotalAmount(), deliveryAddress, paymentMethod);

        outboxService.saveEvent("Order", order.getId().toString(),
                "OrderCreated", event);

        log.info("Order created with Outbox event: orderId={}, sagaId={}", order.getId(), sagaId);
        return order;
    }

    public Order getOrder(Long orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS);
        }

        order.updateStatus(OrderStatus.CANCELLED);

        // Update saga state
        sagaStateRepository.findByOrderId(orderId)
                .ifPresent(saga -> saga.fail("User cancelled"));

        return order;
    }

    // ★ Circuit Breaker fallback
    @SuppressWarnings("unused")
    private Order createOrderFallback(Long userId, Long storeId, List<Long> menuIds,
                                      String paymentMethod, String deliveryAddress,
                                      Throwable t) {
        log.warn("Store service unavailable, order creation failed: {}", t.getMessage());
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "Store service is temporarily unavailable. Please try again later.");
    }
}
