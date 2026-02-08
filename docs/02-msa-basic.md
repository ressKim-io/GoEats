# MSA Basic 아키텍처 상세

도메인별로 서비스를 분리하여 **독립 배포 및 스케일링**이 가능한 구조입니다.
Kafka 이벤트, OpenFeign HTTP 통신, Resilience4j Circuit Breaker, Redisson 분산 락을 사용합니다.

---

## 디렉토리 구조

```
msa/
├── common/
│   ├── common-dto/            # Kafka 이벤트 스키마 (record)
│   ├── common-security/       # JWT 인증 필터
│   └── common-exception/      # 공통 예외 처리
├── order-service/     (:8081) # 주문 + Saga Orchestrator
├── store-service/     (:8082) # 가게 + 메뉴 (읽기 전용)
├── payment-service/   (:8083) # 결제 처리
└── delivery-service/  (:8084) # 배달 + 기사 매칭
```

---

## 핵심 패턴

### 1. Choreography Saga (Kafka 이벤트 기반)

서비스 간 트랜잭션을 Kafka 이벤트로 조율합니다.

```
Order → (Kafka) → Payment → (Kafka) → Delivery
                          → (Kafka) → Order (상태 업데이트)
```

```java
// msa/order-service/.../service/OrderService.java
@Transactional
@CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")
public Order createOrder(Long userId, Long storeId, ...) {
    // 1. Store 조회 (OpenFeign HTTP)
    StoreServiceClient.StoreResponse store = storeServiceClient.getStore(storeId);

    // 2. Order 로컬 저장
    Order order = orderRepository.save(order);

    // 3. Kafka 이벤트 발행 (비동기)
    eventPublisher.publishOrderCreated(
        new OrderCreatedEvent(order.getId(), userId, storeId, totalPrice, paymentMethod));

    return order;
}
```

**문제점:** `orderRepository.save()` 성공 후 `kafkaTemplate.send()`가 실패하면 이벤트 유실

### 2. OpenFeign (서비스 간 HTTP 통신)

동기 HTTP 호출로 다른 서비스의 데이터를 조회합니다.

```java
// msa/order-service/.../client/StoreServiceClient.java
@FeignClient(name = "store-service", url = "${store.service.url}")
public interface StoreServiceClient {

    @GetMapping("/api/stores/{storeId}")
    StoreResponse getStore(@PathVariable Long storeId);

    @GetMapping("/api/menus/{menuId}")
    MenuResponse getMenu(@PathVariable Long menuId);
}
```

**장점:** REST API처럼 직관적인 인터페이스
**한계:** 타임아웃, 장애 전파 위험 (Circuit Breaker 필요)

### 3. Resilience4j Circuit Breaker

외부 서비스 장애 시 빠른 실패로 연쇄 장애를 방지합니다.

```java
// msa/order-service/.../service/OrderService.java
@CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")
public Order createOrder(...) { ... }

private Order createOrderFallback(Long userId, Long storeId, ..., Throwable t) {
    log.error("Store service unavailable: {}", t.getMessage());
    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
}
```

```yaml
# msa/order-service/src/main/resources/application.yml
resilience4j:
  circuitbreaker:
    instances:
      storeService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
```

**동작:** 10개 요청 중 50% 실패 → 회로 열림 → 30초 대기 → Half-Open → 3개 시험 요청

### 4. Kafka 이벤트 수신 (@KafkaListener)

```java
// msa/payment-service/.../event/OrderEventListener.java
@KafkaListener(topics = "order-events", groupId = "payment-service")
public void handleOrderCreated(OrderCreatedEvent event) {
    Payment payment = paymentService.processPayment(
        event.orderId(), event.totalPrice(), event.paymentMethod());
    // 결과 이벤트 발행
}
```

**문제점:**
- 처리 실패 시 메시지 유실 (재시도 없음)
- 중복 이벤트 수신 시 중복 처리 가능
- Dead Letter 처리 없음

### 5. Redisson 분산 락

여러 인스턴스에서 동시에 같은 리소스에 접근하는 것을 방지합니다.

```java
// msa/delivery-service/.../service/DeliveryService.java
@Transactional
public Delivery createDelivery(Long orderId, String deliveryAddress) {
    String lockKey = "lock:rider-assignment:" + orderId;
    RLock lock = redissonClient.getLock(lockKey);

    boolean acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
    if (!acquired) {
        throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
    }

    try {
        // 기사 매칭 로직
        delivery.assignRider(riderName, riderPhone);
    } finally {
        if (lock.isLocked() && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
```

**문제점:** 락 만료(3초) 후 Thread A가 여전히 실행 중이면 Thread B가 새 락을 획득하고,
이후 Thread A가 재개되어 stale write가 발생할 수 있음

### 6. Redis 분산 캐시

```java
// msa/store-service/.../service/StoreService.java
@Cacheable(value = "stores", key = "#id")
public Store getStore(Long id) {
    return storeRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
}
```

**vs Monolithic:** Caffeine(로컬) → Redis(분산)으로 인스턴스 간 캐시 공유 가능

---

## MSA Basic의 한계점

이 한계들이 MSA Traffic에서 해결됩니다.

| 한계 | 설명 | MSA Traffic 해결책 |
|------|------|-------------------|
| 이벤트 유실 | `kafkaTemplate.send()` 실패 시 이벤트 사라짐 | Transactional Outbox |
| 메시지 재처리 없음 | Consumer 실패 시 메시지 유실 | @RetryableTopic + DLQ |
| 중복 처리 | Kafka 재시도로 같은 이벤트 2번 처리 | ProcessedEvent + eventId |
| Rate Limiting 없음 | 과부하 시 서비스 다운 | RateLimiter + Gateway |
| 스레드 격리 없음 | 하나의 느린 서비스가 전체 스레드 점유 | Bulkhead |
| Stale Lock | 분산 락 만료 후 쓰기 발생 | Fencing Token |
| 콜드 스타트 | 서비스 재시작 시 캐시 미스 폭주 | Cache Warming |
| Saga 추적 불가 | Saga 진행 상태 확인 불가 | SagaState 엔티티 |
| 모니터링 부재 | 장애 감지 어려움 | Prometheus + Actuator |

---

## 코드 위치 가이드

| 패턴 | 파일 경로 |
|------|---------|
| 주문 생성 (Saga) | `msa/order-service/.../service/OrderService.java` |
| Kafka 이벤트 발행 | `msa/order-service/.../event/OrderEventPublisher.java` |
| 이벤트 수신 (Payment) | `msa/payment-service/.../event/OrderEventListener.java` |
| 이벤트 수신 (Delivery) | `msa/delivery-service/.../event/PaymentEventListener.java` |
| Circuit Breaker | `msa/order-service/.../service/OrderService.java` |
| Resilience4j 설정 | `msa/order-service/.../application.yml` |
| 분산 락 (Redisson) | `msa/delivery-service/.../service/DeliveryService.java` |
| Redis 캐시 | `msa/store-service/.../service/StoreService.java` |
| OpenFeign 클라이언트 | `msa/order-service/.../client/StoreServiceClient.java` |
