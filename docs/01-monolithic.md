# Monolithic 아키텍처 상세

모든 도메인(User, Store, Order, Payment, Delivery)이 **하나의 Spring Boot 애플리케이션**으로 구성됩니다.
단일 DB, 단일 트랜잭션, 직접 메서드 호출로 구현이 단순하지만 확장성에 한계가 있습니다.

---

## 디렉토리 구조

```
monolithic/
└── src/main/java/com/goeats/
    ├── GoEatsApplication.java     # 단일 진입점
    ├── common/
    │   ├── config/                # WebMvc, Cache 설정
    │   ├── exception/             # GlobalExceptionHandler
    │   └── dto/                   # ApiResponse
    ├── user/                      # 사용자 CRUD
    ├── store/                     # 가게 + 메뉴
    ├── order/                     # 주문 (핵심 흐름)
    ├── payment/                   # 결제
    └── delivery/                  # 배달
```

---

## 핵심 패턴

### 1. 단일 @Transactional

주문 생성 시 Order, Payment, Delivery가 **하나의 트랜잭션**으로 처리됩니다.
어느 단계에서든 예외 발생 시 전체가 자동 롤백됩니다.

```java
// monolithic/.../order/service/OrderService.java
@Transactional
public Order createOrder(Long userId, Long storeId, List<Long> menuIds,
                         String paymentMethod, String deliveryAddress) {
    User user = userService.getUser(userId);
    Store store = storeService.getStore(storeId);

    Order order = Order.builder()
        .user(user).store(store).status(OrderStatus.PENDING).build();
    order = orderRepository.save(order);

    // 같은 트랜잭션 내에서 결제 + 배달 처리
    Payment payment = paymentService.processPayment(order, paymentMethod);
    Delivery delivery = deliveryService.createDelivery(order);

    order.updateStatus(OrderStatus.PAID);
    return order;
}
```

**장점:** 데이터 일관성이 자동으로 보장됨
**한계:** 모든 로직이 같은 프로세스에서 실행되어 부분 스케일링 불가

### 2. JPA 연관관계 (직접 Join)

엔티티 간 FK 관계로 직접 참조하며, 같은 DB에서 Join이 가능합니다.

```java
// Order → User, Store, Payment, Delivery 모두 직접 참조
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id")
private User user;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "store_id")
private Store store;
```

**장점:** 복잡한 쿼리도 JPA Join으로 간단하게 해결
**한계:** MSA에서는 서비스별 DB가 분리되어 Join 불가

### 3. ApplicationEventPublisher (JVM 내 이벤트)

같은 JVM 내에서 동기적으로 이벤트를 발행합니다.

```java
// monolithic/.../order/service/OrderService.java
eventPublisher.publishEvent(new PaymentCompletedEvent(order));

// 같은 JVM 내에서 수신
@EventListener
public void handlePaymentCompleted(PaymentCompletedEvent event) {
    // 동기 처리
}
```

**장점:** 이벤트 유실 없음 (같은 프로세스)
**한계:** 프로세스 간 통신 불가, 외부 시스템 연동 어려움

### 4. Caffeine Cache (로컬 캐시)

JVM 힙 메모리에 데이터를 캐싱합니다.

```java
// monolithic/.../store/service/StoreService.java
@Cacheable(value = "stores", key = "#id")
public Store getStore(Long id) {
    return storeRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
}
```

```yaml
# monolithic/src/main/resources/application.yml
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m
```

**장점:** 외부 인프라(Redis) 불필요, 매우 빠름
**한계:** 인스턴스 간 캐시 공유 불가, 수평 확장 시 캐시 불일치

### 5. @Lock (DB 비관적 락)

동시 접근 제어를 DB 레벨에서 처리합니다.

```java
// monolithic/.../delivery/repository/DeliveryRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM Delivery d WHERE d.order.id = :orderId")
Optional<Delivery> findByOrderIdWithLock(@Param("orderId") Long orderId);
```

**장점:** 구현이 단순하고 트랜잭션 내에서 안전하게 동작
**한계:** DB에 부하 집중, 분산 환경에서 사용 불가

---

## 장점 요약

- 구현이 단순하고 디버깅이 쉬움
- 단일 트랜잭션으로 데이터 일관성 보장
- 인프라 요구사항 최소 (DB만 필요)
- 빠른 개발 및 배포

## 한계 요약

- 서비스별 독립 배포/스케일링 불가
- 단일 장애점 (하나의 버그가 전체 영향)
- DB 병목 (모든 도메인이 같은 DB 사용)
- 팀이 커지면 코드 충돌 및 배포 지연

---

## 코드 위치 가이드

| 패턴 | 파일 경로 |
|------|---------|
| 주문 생성 | `monolithic/.../order/service/OrderService.java` |
| 결제 처리 | `monolithic/.../payment/service/PaymentService.java` |
| 배달 생성 | `monolithic/.../delivery/service/DeliveryService.java` |
| 로컬 캐시 | `monolithic/.../store/service/StoreService.java` |
| 이벤트 발행 | `monolithic/.../order/service/OrderService.java` (publishEvent) |
| DB 락 | `monolithic/.../delivery/repository/DeliveryRepository.java` |
