# 3-Way 패턴별 코드 비교

Monolithic, MSA Basic, MSA Traffic에서 동일한 기능이 어떻게 다르게 구현되는지 비교합니다.

---

## 이벤트 발행

| Monolithic | MSA Basic | MSA Traffic |
|-----------|-----------|-------------|
| `eventPublisher.publishEvent()` | `kafkaTemplate.send()` | `outboxService.saveEvent()` |
| JVM 내부 동기 이벤트 | Kafka 비동기 (트랜잭션 밖) | **트랜잭션 내부 Outbox 저장** |
| 유실 없음 | DB 커밋 후 Kafka 실패 → 유실 | @Scheduled 릴레이가 **StreamBridge**로 전송 |

```java
// Monolithic - 같은 JVM 내 동기 이벤트
eventPublisher.publishEvent(new PaymentCompletedEvent(order));

// MSA Basic - 이벤트 유실 가능
@Transactional
public Order createOrder(...) {
    orderRepository.save(order);                              // 1. DB 커밋
    kafkaTemplate.send("order-events", event);                // 2. ← 여기서 실패하면 유실!
}

// MSA Traffic - Transactional Outbox (원자성 보장)
@Transactional
public Order createOrder(...) {
    orderRepository.save(order);                              // 1. DB 저장
    outboxService.saveEvent("Order", order.getId().toString(), // 2. 같은 트랜잭션으로 Outbox 저장
        "OrderCreated", event);
    // → @Scheduled OutboxRelay가 StreamBridge로 전송 (브로커 독립)
}
```

---

## 이벤트 수신

| Monolithic | MSA Basic | MSA Traffic |
|-----------|-----------|-------------|
| `@EventListener` | `@KafkaListener` | **함수형 Consumer** + `@Service` Handler |
| 실패 시 예외 전파 | 실패 시 메시지 유실 | **4회 재시도 → DLQ 이동** |
| - | 중복 처리 가능 | **ProcessedEvent로 멱등성 보장** |

```java
// Monolithic - 동기 이벤트 리스너
@EventListener
public void handlePaymentCompleted(PaymentCompletedEvent event) {
    // 실패 시 호출자에게 예외 전파 → 롤백
}

// MSA Basic - 실패 시 메시지 유실
@KafkaListener(topics = "payment-events")
public void handlePaymentCompleted(PaymentCompletedEvent event) {
    // 처리 실패 → 메시지 유실, 중복 체크 없음
}

// MSA Traffic - 함수형 Consumer + Handler + DLQ + 멱등성
// @Configuration (Consumer 빈 등록)
@Bean
public Consumer<Message<String>> handlePaymentCompleted(PaymentEventHandler handler) {
    return message -> {
        PaymentCompletedEvent event = objectMapper.readValue(
            message.getPayload(), PaymentCompletedEvent.class);
        handler.handleCompleted(event);  // @Transactional 위임
    };
}

// @Service (트랜잭션 처리)
@Transactional
public void handleCompleted(PaymentCompletedEvent event) {
    if (processedEventRepository.existsById(event.eventId())) return; // ★ 멱등성
    // 처리 로직...
    processedEventRepository.save(new ProcessedEvent(event.eventId()));
}
// ★ 4회 실패 후 → DLQ 토픽 (바인더 레벨 enableDlq: true)
```

---

## 장애 복원력 (Resilience)

| 패턴 | Monolithic | MSA Basic | MSA Traffic |
|------|-----------|-----------|-------------|
| Circuit Breaker | - | `@CircuitBreaker` | `@CircuitBreaker` |
| Retry | try-catch 재시도 | - | `@Retry(maxAttempts=3)` |
| Bulkhead | - | - | `@Bulkhead(maxConcurrent=20)` |
| Rate Limiter | - | - | `@RateLimiter(limitForPeriod=50)` |
| Time Limiter | - | - | `@TimeLimiter(timeout=5s)` |
| Gateway Rate Limit | - | - | Redis Token Bucket |
| Cascading Timeout | - | - | Gateway(8s) > Service(5s) > Feign(3s) |

```java
// Monolithic - 에러 처리 없음
public Order createOrder(...) {
    paymentService.processPayment(order, paymentMethod);  // 실패 → 롤백
}

// MSA Basic - Circuit Breaker만
@CircuitBreaker(name = "storeService", fallbackMethod = "fallback")
public Order createOrder(...) { ... }

// MSA Traffic - 전체 Resilience 스택
@Retry(name = "storeService")           // 1차: 재시도 (3회, 500ms 간격)
@CircuitBreaker(name = "storeService")   // 2차: 회로 차단 (50% 실패율)
@Bulkhead(name = "orderCreation")        // 3차: 동시 요청 20개 제한
public Order createOrder(...) { ... }
```

---

## 분산 락

| Monolithic | MSA Basic | MSA Traffic |
|-----------|-----------|-------------|
| `@Lock(PESSIMISTIC_WRITE)` | Redisson `RLock` | Redisson + **Fencing Token** |
| DB 레벨 락 | Redis 분산 락 | 분산 락 + stale write 방지 |

```java
// Monolithic - DB 비관적 락
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT d FROM Delivery d WHERE d.order.id = :orderId")
Optional<Delivery> findByOrderIdWithLock(@Param("orderId") Long orderId);

// MSA Basic - 락 만료 후 stale write 가능
RLock lock = redissonClient.getLock(lockKey);
lock.tryLock(5, 3, TimeUnit.SECONDS);
delivery.assignRider(riderName, riderPhone);  // ← 락 만료 후에도 실행 가능!

// MSA Traffic - Fencing Token으로 stale write 차단
RAtomicLong counter = redissonClient.getAtomicLong("fencing:" + orderId);
long fencingToken = counter.incrementAndGet();
int updated = deliveryRepository.updateWithFencingToken(
    id, riderName, riderPhone, fencingToken);
if (updated == 0) throw new BusinessException(STALE_LOCK_DETECTED);
```

---

## 캐시 전략

| Monolithic | MSA Basic | MSA Traffic |
|-----------|-----------|-------------|
| Caffeine `@Cacheable` | Redis `@Cacheable` | Redis + **다단계 Fallback** |
| - | - | **Cache Warming** (서버 시작 시) |
| - | - | **캐시별 TTL 설정** |

```java
// Monolithic - 로컬 캐시 (Caffeine)
@Cacheable(value = "stores", key = "#id")
public Store getStore(Long id) {
    return storeRepository.findById(id).orElseThrow(...);
}

// MSA Basic - Redis 분산 캐시
@Cacheable(value = "stores", key = "#id")
public Store getStore(Long id) {
    return storeRepository.findById(id).orElseThrow(...);
}

// MSA Traffic - 다단계 Fallback
@Cacheable(value = "stores", key = "#id")                      // Level 1: Redis
@CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreFallback")
public Store getStore(Long id) {
    return storeRepository.findById(id).orElseThrow(...);       // Level 2: DB
}

private Store getStoreFallback(Long id, Throwable t) {
    Object cached = redisTemplate.opsForValue().get("stores::" + id);
    if (cached instanceof Store store) return store;            // Level 3: 수동 Redis
    throw new BusinessException(SERVICE_UNAVAILABLE);            // Level 4: 에러
}
```

---

## Saga 패턴

| 항목 | Monolithic | MSA Basic | MSA Traffic |
|------|-----------|-----------|-------------|
| 트랜잭션 | `@Transactional` (단일 DB) | **Choreography** Saga (이벤트 기반) | **Orchestration** Saga (Command/Reply) |
| 흐름 제어 | 단일 메서드 내 순차 호출 | 각 서비스가 독립적으로 이벤트 구독 | Orchestrator가 중앙 제어 |
| 보상 트랜잭션 | 자동 롤백 | 각 서비스에 보상 로직 분산 | Orchestrator가 보상 결정 |
| 상태 추적 | 불필요 | SagaState (관찰 전용, String) | SagaState (의사결정 기반, SagaStep enum) |
| 디버깅 | 단일 트랜잭션 로그 | 전체 흐름 파악 어려움 | Orchestrator 로그로 전체 흐름 추적 |

```java
// Monolithic - 단일 @Transactional
@Transactional
public Order createOrder(...) {
    orderRepository.save(order);
    paymentService.processPayment(order);  // 실패 → 전체 롤백
    deliveryService.createDelivery(order);
}

// MSA Basic - Choreography Saga
// Order → OrderCreatedEvent → Payment (독립 구독)
//                              → PaymentCompletedEvent → Delivery (독립 구독)
kafkaTemplate.send("order-events", event);  // 각 서비스가 스스로 판단

// MSA Traffic - Orchestration Saga
// Orchestrator → PaymentCommand → Payment → SagaReply → Orchestrator
//             → DeliveryCommand → Delivery → SagaReply → Orchestrator
sagaOrchestrator.startSaga(sagaId, order);  // 중앙 제어자가 순차 지시
```

---

## 주문 생성 전체 비교

### Monolithic

```java
@Transactional
public Order createOrder(...) {
    User user = userService.getUser(userId);           // 메서드 호출
    Store store = storeService.getStore(storeId);      // 메서드 호출
    Order order = orderRepository.save(order);         // 같은 DB
    paymentService.processPayment(order, method);      // 같은 트랜잭션
    deliveryService.createDelivery(order);             // 같은 트랜잭션
    return order;  // 실패 시 전체 롤백
}
```

### MSA Basic

```java
@Transactional
@CircuitBreaker(name = "storeService", fallbackMethod = "fallback")
public Order createOrder(...) {
    var store = storeServiceClient.getStore(storeId);  // OpenFeign HTTP
    Order order = orderRepository.save(order);         // order_db만
    kafkaTemplate.send("order-events", event);          // Kafka 직접 호출
    return order;  // Payment는 비동기 Kafka로 처리
}
```

### MSA Traffic (Orchestration Saga)

```java
@Transactional
@Retry(name = "storeService")
@CircuitBreaker(name = "storeService", fallbackMethod = "fallback")
@Bulkhead(name = "orderCreation")
public Order createOrder(...) {
    var store = storeServiceClient.getStore(storeId);  // Feign + Retry + CB
    Order order = orderRepository.save(order);         // order_db
    sagaStateRepository.save(sagaState);               // Saga 추적 (SagaStep enum)
    sagaOrchestrator.startSaga(sagaId, order);         // ★ Orchestrator가 PaymentCommand 발행
    return order;  // Outbox Relay → payment-commands → Payment Service
}
// Payment Service가 SagaReply → saga-replies → Orchestrator
// Orchestrator가 DeliveryCommand → delivery-commands → Delivery Service
```

---

## 패턴 적용 요약 매트릭스

| 패턴 | Monolithic | MSA Basic | MSA Traffic |
|------|:---------:|:---------:|:-----------:|
| 단일 @Transactional | O | - | - |
| Saga (Choreography) | - | O | - |
| Saga (Orchestration) | - | - | O |
| Transactional Outbox | - | - | O |
| Spring Cloud Stream + DLQ | - | - | O |
| Idempotent Consumer | - | - | O |
| Circuit Breaker | - | O | O |
| Retry | - | - | O |
| Bulkhead | - | - | O |
| RateLimiter | - | - | O |
| TimeLimiter | - | - | O |
| Fencing Token | - | - | O |
| Cache Warming | - | - | O |
| Multi-level Fallback | - | - | O |
| ShedLock | - | - | O |
| SagaState 추적 | - | - | O |
| Prometheus 메트릭 | - | - | O |
| API Gateway | - | - | O |
| Idempotency-Key | - | - | O |
| Spring Cloud Stream (브로커 추상화) | - | - | O |
| Redis 주문 대기열 (Sorted Set) | - | - | O |
| Redis Pub/Sub (실시간 알림) | - | - | O |
