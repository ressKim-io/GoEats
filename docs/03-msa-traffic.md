# MSA Traffic - 프로덕션급 패턴 상세

MSA Basic의 한계를 해결하기 위해 **12가지 프로덕션 패턴**을 적용한 구조입니다.
각 패턴별로 **어떤 문제를 해결하는지**, **어떻게 동작하는지**, **주의점은 무엇인지** 설명합니다.

---

## 디렉토리 구조

```
msa-traffic/
├── common/
│   ├── common-dto/            # 모든 이벤트에 eventId 추가
│   ├── common-security/       # JWT (Gateway 연동)
│   ├── common-exception/      # Resilience4j 예외 핸들러
│   ├── common-outbox/         # ★ Transactional Outbox
│   └── common-resilience/     # ★ Resilience4j + Prometheus
├── gateway-service/   (:8080) # ★ API Gateway
├── order-service/     (:8081) # Outbox, Saga State, 멱등성
├── store-service/     (:8082) # Cache Warming, 다단계 Fallback
├── payment-service/   (:8083) # 멱등 컨슈머, DLQ, Outbox
└── delivery-service/  (:8084) # Fencing Token, Bulkhead, ShedLock
```

---

## 1. Transactional Outbox Pattern

### 문제
`kafkaTemplate.send()`는 DB 트랜잭션 밖에서 실행됩니다.
DB 커밋 성공 → Kafka 전송 실패 시 이벤트가 유실됩니다.

### 해결
비즈니스 데이터와 이벤트를 **같은 DB 트랜잭션**에 저장합니다.
별도의 `@Scheduled` 릴레이가 미발행 이벤트를 폴링하여 Kafka로 전송합니다.

```java
// msa-traffic/common/common-outbox/.../OutboxService.java
public void saveEvent(String aggregateType, String aggregateId,
                      String eventType, Object event) {
    String payload = objectMapper.writeValueAsString(event);
    OutboxEvent outboxEvent = OutboxEvent.builder()
        .aggregateType(aggregateType)
        .aggregateId(aggregateId)
        .eventType(eventType)
        .payload(payload)
        .build();
    outboxEventRepository.save(outboxEvent);  // 호출자의 @Transactional 내에서 실행
}
```

```java
// msa-traffic/common/common-outbox/.../OutboxRelay.java
@Scheduled(fixedDelay = 1000)
@Transactional
public void publishPendingEvents() {
    List<OutboxEvent> pendingEvents =
        outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

    for (OutboxEvent event : pendingEvents) {
        String topic = resolveTopicName(event.getEventType());
        kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload());
        event.markPublished();
    }
}
```

### 주의점
- **폴링 지연**: 최대 1초 지연 발생 (fixedDelay=1000). 실시간성이 중요하면 CDC(Change Data Capture) 고려
- **순서 보장**: 같은 aggregate 내에서만 순서 보장됨. 서로 다른 aggregate 간 순서는 보장 불가
- **중복 발행 가능**: Kafka 전송 성공 후 DB 업데이트 전 서버 다운 시 → Consumer 측 멱등성 필수
- **OutboxEvent 정리**: published=true인 이벤트를 주기적으로 삭제하는 배치 필요

---

## 2. @RetryableTopic + @DltHandler (Dead Letter Queue)

### 문제
`@KafkaListener`에서 처리 실패 시 메시지가 유실됩니다.

### 해결
Spring Kafka의 `@RetryableTopic`으로 자동 재시도하고, 최종 실패 시 DLT(Dead Letter Topic)로 이동합니다.

```java
// msa-traffic/payment-service/.../event/OrderEventListener.java
@RetryableTopic(
    attempts = "4",
    backoff = @Backoff(delay = 1000, multiplier = 2.0),
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    dltStrategy = DltStrategy.ALWAYS_RETRY_ON_ERROR
)
@KafkaListener(topics = "order-events", groupId = "payment-service")
@Transactional
public void handleOrderCreated(OrderCreatedEvent event) {
    // 멱등성 체크 + 처리 로직
}

@DltHandler
public void handleDlt(OrderCreatedEvent event) {
    log.error("Manual intervention required: orderId={}", event.orderId());
}
```

### 재시도 흐름
```
order-events → 1차 실패 (1초 후)
  → order-events-retry-0 → 2차 실패 (2초 후)
    → order-events-retry-1 → 3차 실패 (4초 후)
      → order-events-retry-2 → 4차 실패
        → order-events-dlt (Dead Letter Topic) → @DltHandler
```

### 주의점
- **DLQ 모니터링 필수**: DLT에 쌓인 메시지를 수동으로 처리하는 운영 프로세스 필요
- **재시도 토픽 생성**: retry-0, retry-1, retry-2, dlt 토픽이 자동 생성됨. Kafka 토픽 수 증가
- **재시도 간 상태 변경**: 재시도 사이에 외부 상태가 변경될 수 있음. 멱등성 보장 필수
- **backoff 전략**: exponential backoff(1s, 2s, 4s)로 일시적 장애 복구 시간 확보

---

## 3. Idempotent Consumer (멱등성 보장)

### 문제
Kafka 재시도, Outbox 중복 발행 등으로 같은 이벤트가 여러 번 수신될 수 있습니다.

### 해결
모든 이벤트에 UUID `eventId`를 포함하고, `ProcessedEvent` 테이블로 중복을 차단합니다.

```java
// 모든 이벤트 DTO에 eventId 추가
// msa-traffic/common/common-dto/.../OrderCreatedEvent.java
public record OrderCreatedEvent(
    String eventId,   // ★ UUID for idempotency
    Long orderId,
    Long userId,
    ...
) {
    public OrderCreatedEvent { if (eventId == null) eventId = UUID.randomUUID().toString(); }
}
```

```java
// Consumer 측 중복 체크
if (processedEventRepository.existsById(event.eventId())) {
    log.info("Duplicate event skipped: eventId={}", event.eventId());
    return;
}
// ... 처리 로직 ...
processedEventRepository.save(new ProcessedEvent(event.eventId()));
```

### 주의점
- **ProcessedEvent 테이블 크기**: 시간이 지나면 계속 증가함. 주기적 정리 배치 필요
- **eventId 유일성**: 발행 측에서 UUID 생성을 보장해야 함
- **처리-저장 원자성**: `처리 로직 + ProcessedEvent 저장`이 같은 @Transactional이어야 함

---

## 4. Fencing Token (Stale Lock 방지)

### 문제
분산 락 만료 후 Thread A가 여전히 실행 중이면 stale write가 발생합니다.

```
Thread A: 락 획득 → GC pause → 락 만료 → 재개 → DB 쓰기 (stale!)
Thread B:                       락 획득 → DB 쓰기 (정상)
```

### 해결
Redis AtomicLong으로 단조 증가 토큰을 생성하고, DB UPDATE WHERE 절로 stale write를 차단합니다.

```java
// msa-traffic/delivery-service/.../service/DeliveryService.java
RAtomicLong fencingCounter = redissonClient.getAtomicLong("fencing:rider:" + orderId);
long fencingToken = fencingCounter.incrementAndGet();

// DB UPDATE with token check
int updated = deliveryRepository.updateWithFencingToken(
    id, riderName, riderPhone, fencingToken);
if (updated == 0) {
    throw new BusinessException(ErrorCode.STALE_LOCK_DETECTED);
}
```

```java
// msa-traffic/delivery-service/.../repository/DeliveryRepository.java
@Modifying
@Query("UPDATE Delivery d SET d.riderName = :riderName, d.riderPhone = :riderPhone, " +
       "d.status = com.goeats.delivery.entity.DeliveryStatus.RIDER_ASSIGNED, " +
       "d.lastFencingToken = :fencingToken " +
       "WHERE d.id = :id AND (d.lastFencingToken IS NULL OR d.lastFencingToken < :fencingToken)")
int updateWithFencingToken(@Param("id") Long id, ...);
```

### 동작 흐름
```
Thread A: token=5 → 락 획득 → GC pause → 락 만료
Thread B: token=6 → 락 획득 → DB 쓰기 (6 > NULL) → 성공
Thread A: 재개 → DB 쓰기 시도 (5 < 6) → 0 rows → 예외!
```

### 주의점
- **Redis AtomicLong 영속성**: Redis 재시작 시 카운터 초기화됨. RDB/AOF 설정 필요
- **성능 오버헤드**: 매 요청마다 Redis increment + DB conditional update
- **실패 처리**: `updated == 0`일 때 적절한 재시도 또는 보상 로직 필요

---

## 5. Resilience4j 5패턴 스택

### 문제
Circuit Breaker만으로는 과부하, 타임아웃, 재시도, 스레드 고갈을 모두 방지할 수 없습니다.

### 해결
5가지 패턴을 조합하여 계층적으로 보호합니다.

```java
// msa-traffic/order-service/.../service/OrderService.java
@Retry(name = "storeService")           // 1단계: 일시적 실패 재시도
@CircuitBreaker(name = "storeService")   // 2단계: 연속 실패 시 회로 차단
@Bulkhead(name = "orderCreation")        // 3단계: 동시 요청 수 제한
public Order createOrder(...) { ... }
```

```yaml
# msa-traffic/order-service/src/main/resources/application.yml
resilience4j:
  retry:
    instances:
      storeService:
        maxAttempts: 3
        waitDuration: 500ms

  circuitbreaker:
    instances:
      storeService:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s

  bulkhead:
    instances:
      orderCreation:
        maxConcurrentCalls: 20
        maxWaitDuration: 500ms

  ratelimiter:
    instances:
      orderApi:
        limitForPeriod: 50
        limitRefreshPeriod: 1s

  timelimiter:
    instances:
      storeService:
        timeoutDuration: 5s
```

### 패턴별 역할

| 패턴 | 역할 | 적용 위치 |
|------|------|---------|
| **Retry** | 일시적 네트워크 오류 재시도 (3회, 500ms) | Service Layer |
| **Circuit Breaker** | 연속 실패 시 빠른 실패 (50% 실패율) | Service Layer |
| **Bulkhead** | 동시 요청 수 제한 (20개) | Service Layer |
| **RateLimiter** | 초당 요청 수 제한 (50/s) | Controller Layer |
| **TimeLimiter** | 응답 타임아웃 (5s) | Feign Client |

### 주의점
- **애노테이션 순서**: `@Retry` → `@CircuitBreaker` → `@Bulkhead` 순서로 적용됨 (외부→내부)
- **Retry + CB 상호작용**: Retry 실패가 CB에 기록됨. maxAttempts를 너무 높이면 CB가 빨리 열림
- **Bulkhead maxWaitDuration**: 0으로 설정하면 즉시 거부. 너무 높으면 요청 대기 큐 증가

---

## 6. API Gateway + Rate Limiting

### 문제
각 서비스에 직접 접근하면 인증/인가 로직이 중복되고, 전체 트래픽 제어가 어렵습니다.

### 해결
Spring Cloud Gateway로 단일 진입점을 구성하고, Redis 기반 사용자별 Rate Limiting을 적용합니다.

```yaml
# msa-traffic/gateway-service/src/main/resources/application.yml
spring:
  cloud:
    gateway:
      httpclient:
        response-timeout: 8000   # Gateway 타임아웃
      routes:
        - id: order-service
          uri: http://localhost:8081
          predicates:
            - Path=/api/orders/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 50
                redis-rate-limiter.burstCapacity: 100
                key-resolver: "#{@userKeyResolver}"
```

```java
// msa-traffic/gateway-service/.../config/RateLimitConfig.java
@Bean
public KeyResolver userKeyResolver() {
    return exchange -> {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
        if (userId != null) return Mono.just(userId);
        String ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
        return Mono.just(ip);
    };
}
```

### 계단식 타임아웃
```
Gateway (8s) > Service 내부 (5s) > Feign Client (3s)
```
안쪽이 먼저 타임아웃 → 바깥에서 적절한 에러 처리 가능

### 주의점
- **Gateway 단일 장애점**: Gateway 자체가 다운되면 전체 서비스 접근 불가. 다중 인스턴스 운영 필요
- **Redis 의존성**: Rate Limiter가 Redis에 의존. Redis 다운 시 Rate Limiting 비활성화 (기본: allow)
- **타임아웃 계단**: 반드시 Gateway > Service > Client 순서 유지

---

## 7. Idempotency-Key (API 중복 요청 방지)

### 문제
네트워크 재시도, 사용자 더블 클릭 등으로 같은 주문이 중복 생성될 수 있습니다.

### 해결
클라이언트가 `Idempotency-Key` 헤더에 고유 키를 보내면, Redis SET NX로 중복을 차단합니다.

```java
// msa-traffic/order-service/.../controller/OrderController.java
@PostMapping
@RateLimiter(name = "orderApi")
public ApiResponse<Order> createOrder(
    @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
    ...) {
    if (idempotencyKey != null) {
        String redisKey = "idempotency:order:" + idempotencyKey;
        Boolean isNew = redisTemplate.opsForValue()
            .setIfAbsent(redisKey, "processing", Duration.ofHours(24));
        if (Boolean.FALSE.equals(isNew)) {
            throw new BusinessException(ErrorCode.DUPLICATE_REQUEST);
        }
    }
    return ApiResponse.ok(orderService.createOrder(...));
}
```

### 주의점
- **TTL 관리**: 24시간 후 같은 키로 재요청 가능. 비즈니스 요구사항에 맞게 조정
- **클라이언트 협력 필요**: 클라이언트가 키를 보내지 않으면 보호 불가 (required=false)
- **결과 캐싱 미포함**: 현재 구현은 중복 차단만 하고 이전 결과를 반환하지 않음

---

## 8. Cache Warming

### 문제
서비스 재시작 시 Redis 캐시가 비어있어 DB에 요청이 폭주합니다 (Cache Stampede).

### 해결
`ApplicationRunner`로 서버 시작 시 인기 데이터를 Redis에 프리로드합니다.

```java
// msa-traffic/store-service/.../config/CacheWarmingRunner.java
@Component
public class CacheWarmingRunner implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        List<Store> openStores = storeRepository.findByOpenTrue();
        for (Store store : openStores) {
            redisTemplate.opsForValue().set(
                "stores::" + store.getId(), store, Duration.ofMinutes(30));
        }
        log.info("Cache warming completed: {} stores pre-loaded", openStores.size());
    }
}
```

### 주의점
- **시작 시간 증가**: 데이터가 많으면 서비스 시작이 느려짐. 비동기 처리 고려
- **TTL 정합성**: Warming에서 설정한 TTL과 `@Cacheable`의 TTL이 일치해야 함
- **부분 실패**: Warming 중 Redis 연결 실패 시 서비스 시작에 영향 없도록 예외 처리 필요

---

## 9. Multi-level Cache Fallback

### 문제
DB 장애 시 캐시 미스가 곧바로 에러로 이어집니다.

### 해결
4단계 Fallback으로 가용성을 극대화합니다.

```java
// msa-traffic/store-service/.../service/StoreService.java
@Cacheable(value = "stores", key = "#id")                     // Level 1: Redis
@CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreFallback")
public Store getStore(Long id) {
    return storeRepository.findById(id)                        // Level 2: DB
        .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
}

private Store getStoreFallback(Long id, Throwable t) {
    Object cached = redisTemplate.opsForValue().get("stores::" + id);
    if (cached instanceof Store store) return store;           // Level 3: 수동 Redis
    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); // Level 4: 에러
}
```

```
요청 → @Cacheable(Redis hit?) → Yes → 반환
                                 No ↓
                              DB 조회 → 성공 → 반환 (+ Redis 캐싱)
                                        실패 ↓
                              CircuitBreaker Fallback
                              → 수동 Redis 확인 → 있으면 반환
                                                  없으면 → 503 에러
```

---

## 10. ShedLock

### 문제
delivery-service를 3개 인스턴스로 스케일 아웃하면, `@Scheduled` OutboxRelay가 3개 모두에서 실행됩니다.

### 해결
Redis 기반 ShedLock으로 **하나의 인스턴스만** 스케줄러를 실행합니다.

```java
// msa-traffic/delivery-service/.../config/ShedLockConfig.java
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "30s")
public class ShedLockConfig {
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "delivery-service");
    }
}
```

### 주의점
- **lockAtMostFor**: 스케줄 실행 시간보다 충분히 길게 설정. 짧으면 중복 실행 발생
- **lockAtLeastFor**: 너무 짧으면 빠른 완료 후 다른 인스턴스가 바로 실행. 적절한 간격 유지
- **Redis 의존성**: ShedLock이 Redis에 의존. Redis 다운 시 모든 인스턴스가 실행할 수 있음

---

## 11. Saga State 추적

### 문제
비동기 Saga에서 현재 어느 단계에 있는지, 실패 원인이 무엇인지 추적할 수 없습니다.

### 해결
`SagaState` 엔티티로 Saga의 생명주기를 관리합니다.

```java
// msa-traffic/order-service/.../entity/SagaState.java
@Entity
public class SagaState {
    private String sagaId;           // UUID
    private String sagaType;         // "CREATE_ORDER"
    @Enumerated(EnumType.STRING)
    private SagaStatus status;       // STARTED → COMPENSATING → COMPLETED / FAILED
    private String currentStep;      // "ORDER_CREATED", "PAYMENT_PENDING", etc.
    private String failureReason;
    private Long orderId;
}
```

### 상태 전이
```
STARTED → (결제 성공) → COMPLETED
        → (결제 실패) → COMPENSATING → FAILED
```

---

## 12. Prometheus 모니터링

### 문제
Resilience4j 패턴들의 동작 상태를 실시간으로 확인할 수 없습니다.

### 해결
Micrometer + Prometheus로 모든 Resilience4j 메트릭을 노출합니다.

```java
// msa-traffic/common/common-resilience/.../ResilienceMetricsConfig.java
TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(cbRegistry).bindTo(meterRegistry);
TaggedRetryMetrics.ofRetryRegistry(retryRegistry).bindTo(meterRegistry);
TaggedRateLimiterMetrics.ofRateLimiterRegistry(rlRegistry).bindTo(meterRegistry);
TaggedBulkheadMetrics.ofBulkheadRegistry(bhRegistry).bindTo(meterRegistry);
```

**노출 메트릭 예시:**
- `resilience4j_circuitbreaker_state{name="storeService"}` → 0(CLOSED), 1(OPEN)
- `resilience4j_retry_calls_total{name="storeService", kind="successful"}`
- `resilience4j_ratelimiter_available_permissions{name="orderApi"}`
- `resilience4j_bulkhead_available_concurrent_calls{name="orderCreation"}`

---

## HikariCP 커넥션 풀 튜닝

각 서비스의 트래픽 특성에 맞게 커넥션 풀을 튜닝합니다.

```yaml
# msa-traffic/order-service/src/main/resources/application.yml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20    # 최대 커넥션
      minimum-idle: 5          # 최소 유휴 커넥션
      connection-timeout: 3000 # 커넥션 획득 타임아웃 (3초)
      idle-timeout: 600000     # 유휴 커넥션 해제 (10분)
      max-lifetime: 1800000    # 커넥션 최대 수명 (30분)
```

---

## Kafka Producer 보장 수준

```yaml
# msa-traffic/order-service/src/main/resources/application.yml
spring:
  kafka:
    producer:
      acks: all                      # 모든 replica 확인
      retries: 3                     # 전송 실패 시 재시도
      properties:
        enable.idempotence: true     # 멱등한 프로듀서
    consumer:
      enable-auto-commit: false      # 수동 커밋
    listener:
      ack-mode: record               # 레코드 단위 커밋
```

---

## 코드 위치 가이드

| 패턴 | 파일 경로 |
|------|---------|
| Outbox 엔티티 | `msa-traffic/common/common-outbox/.../OutboxEvent.java` |
| Outbox 저장 | `msa-traffic/common/common-outbox/.../OutboxService.java` |
| Outbox 릴레이 | `msa-traffic/common/common-outbox/.../OutboxRelay.java` |
| @RetryableTopic (Payment) | `msa-traffic/payment-service/.../event/OrderEventListener.java` |
| @RetryableTopic (Order) | `msa-traffic/order-service/.../event/PaymentEventListener.java` |
| ProcessedEvent | `msa-traffic/*/event/ProcessedEvent.java` |
| Fencing Token | `msa-traffic/delivery-service/.../service/DeliveryService.java` |
| Fencing Update | `msa-traffic/delivery-service/.../repository/DeliveryRepository.java` |
| Resilience4j 5패턴 | `msa-traffic/order-service/.../service/OrderService.java` |
| Resilience4j 설정 | `msa-traffic/order-service/.../application.yml` |
| Gateway 설정 | `msa-traffic/gateway-service/.../application.yml` |
| Rate Limiting | `msa-traffic/gateway-service/.../config/RateLimitConfig.java` |
| JWT Gateway 필터 | `msa-traffic/gateway-service/.../filter/JwtAuthGatewayFilter.java` |
| Idempotency-Key | `msa-traffic/order-service/.../controller/OrderController.java` |
| Cache Warming | `msa-traffic/store-service/.../config/CacheWarmingRunner.java` |
| 다단계 Fallback | `msa-traffic/store-service/.../service/StoreService.java` |
| Redis 캐시 설정 | `msa-traffic/store-service/.../config/RedisConfig.java` |
| ShedLock | `msa-traffic/delivery-service/.../config/ShedLockConfig.java` |
| SagaState | `msa-traffic/order-service/.../entity/SagaState.java` |
| Prometheus 메트릭 | `msa-traffic/common/common-resilience/.../ResilienceMetricsConfig.java` |
