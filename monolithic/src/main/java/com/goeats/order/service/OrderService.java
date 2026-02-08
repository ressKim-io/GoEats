package com.goeats.order.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.delivery.service.DeliveryService;
import com.goeats.order.entity.Order;
import com.goeats.order.entity.OrderItem;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import com.goeats.payment.service.PaymentService;
import com.goeats.store.entity.Menu;
import com.goeats.store.entity.Store;
import com.goeats.store.service.StoreService;
import com.goeats.user.entity.User;
import com.goeats.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ★★★ CORE COMPARISON POINT ★★★
 *
 * Monolithic OrderService: Everything in ONE @Transactional method.
 *
 * Key characteristics:
 * 1. TRANSACTION: Single @Transactional wraps order + payment + delivery
 *    → Any failure = automatic full rollback
 * 2. SERVICE CALLS: Direct method calls (paymentService.processPayment())
 *    → No network overhead, no serialization
 * 3. DATA ACCESS: JPA relationships (order.getUser(), order.getStore())
 *    → Same database, SQL JOINs
 * 4. ERROR HANDLING: try-catch with automatic rollback
 *    → Simple and predictable
 * 5. EVENTS: ApplicationEventPublisher (Spring internal, same JVM)
 *    → Synchronous, guaranteed delivery
 *
 * Compare with MSA OrderService:
 * 1. TRANSACTION: Saga pattern (Choreography via Kafka events)
 *    → OrderCreatedEvent → PaymentService → PaymentCompletedEvent → DeliveryService
 * 2. SERVICE CALLS: OpenFeign HTTP clients
 *    → Network latency, circuit breaker needed
 * 3. DATA ACCESS: Only orderId stored, fetch via HTTP
 *    → Separate databases per service
 * 4. ERROR HANDLING: Circuit Breaker (Resilience4j) + Fallback
 *    → Complex but resilient
 * 5. EVENTS: Kafka (distributed, persistent, replay-able)
 *    → Asynchronous, at-least-once delivery
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    // ★ Monolithic: Direct service injection - all in same JVM
    private final OrderRepository orderRepository;
    private final UserService userService;
    private final StoreService storeService;
    private final PaymentService paymentService;
    private final DeliveryService deliveryService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * ★ Monolithic: Single transaction covers the entire order flow.
     *
     * Order → Payment → Delivery in ONE database transaction.
     * If payment fails, order creation is also rolled back automatically.
     *
     * Compare with MSA:
     * 1. OrderService.createOrder() → saves order + publishes OrderCreatedEvent to Kafka
     * 2. PaymentService listens → processes payment → publishes PaymentCompletedEvent
     * 3. DeliveryService listens → creates delivery
     * 4. If step 2 fails → publishes PaymentFailedEvent → OrderService cancels order (saga compensation)
     */
    @Transactional
    public Order createOrder(Long userId, Long storeId, List<Long> menuIds,
                             String paymentMethod, String deliveryAddress) {
        log.info("Creating order: userId={}, storeId={}", userId, storeId);

        // 1. Validate user and store (direct method calls)
        User user = userService.getUser(userId);
        Store store = storeService.getStoreWithMenus(storeId);

        if (!store.isOpen()) {
            throw new BusinessException(ErrorCode.STORE_CLOSED);
        }

        // 2. Create order with items
        Order order = Order.builder()
                .user(user)
                .store(store)
                .deliveryAddress(deliveryAddress)
                .build();

        for (Long menuId : menuIds) {
            Menu menu = storeService.getMenu(menuId);
            OrderItem item = OrderItem.builder()
                    .menu(menu)
                    .quantity(1)
                    .build();
            order.addItem(item);
        }

        order = orderRepository.save(order);

        // 3. Process payment (direct method call - same transaction)
        try {
            paymentService.processPayment(order, paymentMethod);
            order.updateStatus(OrderStatus.PAID);
        } catch (BusinessException e) {
            // ★ Monolithic: automatic rollback - order won't be saved either
            log.error("Payment failed, rolling back order: {}", order.getId());
            throw e;
        }

        // 4. Create delivery (direct method call - same transaction)
        deliveryService.createDelivery(order);
        order.updateStatus(OrderStatus.PREPARING);

        // 5. Publish internal Spring event (same JVM, synchronous)
        eventPublisher.publishEvent(new OrderCompletedEvent(order.getId()));

        log.info("Order completed: orderId={}, total={}", order.getId(), order.getTotalAmount());
        return order;
    }

    public Order getOrder(Long orderId) {
        return orderRepository.findWithDetailsById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    public List<Order> getOrdersByUser(Long userId) {
        return orderRepository.findByUserId(userId);
    }

    /**
     * ★ Monolithic: Cancel with direct rollback in same transaction.
     * Refund + cancel delivery + update status - all atomic.
     */
    @Transactional
    public Order cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_STATUS,
                    "Cannot cancel delivered order");
        }

        // Direct calls - all in same transaction
        paymentService.refund(orderId);
        order.updateStatus(OrderStatus.CANCELLED);

        log.info("Order cancelled: {}", orderId);
        return order;
    }

    // Simple record for Spring ApplicationEvent
    public record OrderCompletedEvent(Long orderId) {}
}
