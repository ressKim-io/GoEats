package com.goeats.order.service;

import com.goeats.common.exception.BusinessException;
import com.goeats.common.exception.ErrorCode;
import com.goeats.delivery.entity.Delivery;
import com.goeats.delivery.service.DeliveryService;
import com.goeats.order.entity.Order;
import com.goeats.order.entity.OrderStatus;
import com.goeats.order.repository.OrderRepository;
import com.goeats.payment.entity.Payment;
import com.goeats.payment.service.PaymentService;
import com.goeats.store.entity.Menu;
import com.goeats.store.entity.Store;
import com.goeats.store.service.StoreService;
import com.goeats.user.entity.User;
import com.goeats.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * ★ Monolithic OrderService Test
 *
 * Simple unit test using Mockito - all dependencies are in the same JVM.
 *
 * Compare with MSA:
 * - Need to mock Kafka event publishing
 * - Need to test saga compensation flows
 * - Need to test circuit breaker fallback scenarios
 * - Integration tests require Testcontainers (Kafka, Redis, separate DBs)
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserService userService;
    @Mock
    private StoreService storeService;
    @Mock
    private PaymentService paymentService;
    @Mock
    private DeliveryService deliveryService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("주문 생성 성공 - 주문+결제+배달이 하나의 트랜잭션에서 처리")
    void createOrder_Success() {
        // Given
        User user = User.builder()
                .email("test@example.com")
                .name("Test User")
                .phone("010-1234-5678")
                .address("Seoul")
                .build();

        Store store = Store.builder()
                .name("Test Store")
                .address("Gangnam")
                .phone("02-1234-5678")
                .open(true)
                .build();

        Menu menu = Menu.builder()
                .name("Chicken")
                .price(BigDecimal.valueOf(20000))
                .description("Fried chicken")
                .available(true)
                .build();

        given(userService.getUser(1L)).willReturn(user);
        given(storeService.getStoreWithMenus(1L)).willReturn(store);
        given(storeService.getMenu(1L)).willReturn(menu);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(paymentService.processPayment(any(Order.class), eq("CARD")))
                .willReturn(mock(Payment.class));
        given(deliveryService.createDelivery(any(Order.class)))
                .willReturn(mock(Delivery.class));

        // When
        Order order = orderService.createOrder(1L, 1L, List.of(1L), "CARD", "Seoul");

        // Then
        assertThat(order).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PREPARING);
        assertThat(order.getItems()).hasSize(1);

        // ★ Monolithic: All services called directly in sequence
        verify(paymentService).processPayment(any(), eq("CARD"));
        verify(deliveryService).createDelivery(any());
        verify(eventPublisher).publishEvent(any(OrderService.OrderCompletedEvent.class));
    }

    @Test
    @DisplayName("결제 실패 시 주문도 함께 롤백")
    void createOrder_PaymentFailed_RollbackAll() {
        // Given
        User user = User.builder()
                .email("test@example.com").name("Test").phone("010").address("Seoul").build();
        Store store = Store.builder()
                .name("Store").address("Addr").phone("02").open(true).build();
        Menu menu = Menu.builder()
                .name("Menu").price(BigDecimal.valueOf(10000)).available(true).build();

        given(userService.getUser(1L)).willReturn(user);
        given(storeService.getStoreWithMenus(1L)).willReturn(store);
        given(storeService.getMenu(1L)).willReturn(menu);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        // ★ Payment fails - in monolithic, this triggers automatic transaction rollback
        given(paymentService.processPayment(any(), anyString()))
                .willThrow(new BusinessException(ErrorCode.PAYMENT_FAILED));

        // When & Then
        assertThatThrownBy(() ->
                orderService.createOrder(1L, 1L, List.of(1L), "CARD", "Seoul"))
                .isInstanceOf(BusinessException.class);

        // ★ Monolithic: Delivery should NOT be called when payment fails
        // (automatic rollback, no need for saga compensation)
        verify(deliveryService, never()).createDelivery(any());
    }

    @Test
    @DisplayName("가게가 닫혀있으면 주문 불가")
    void createOrder_StoreClosed_ThrowsException() {
        // Given
        User user = User.builder()
                .email("test@example.com").name("Test").phone("010").address("Seoul").build();
        Store store = Store.builder()
                .name("Store").address("Addr").phone("02").open(false).build();

        given(userService.getUser(1L)).willReturn(user);
        given(storeService.getStoreWithMenus(1L)).willReturn(store);

        // When & Then
        assertThatThrownBy(() ->
                orderService.createOrder(1L, 1L, List.of(1L), "CARD", "Seoul"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.STORE_CLOSED);
                });
    }

    @Test
    @DisplayName("주문 취소 - 환불 + 상태 변경이 하나의 트랜잭션")
    void cancelOrder_Success() {
        // Given
        Order order = mock(Order.class);
        given(order.getStatus()).willReturn(OrderStatus.PREPARING);
        given(orderRepository.findById(1L)).willReturn(Optional.of(order));

        // When
        orderService.cancelOrder(1L);

        // Then - ★ Monolithic: refund and status change in same transaction
        verify(paymentService).refund(1L);
        verify(order).updateStatus(OrderStatus.CANCELLED);
    }
}
