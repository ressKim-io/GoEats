# MSA Traffic - 프로덕션급 패턴 상세

MSA Basic의 한계를 해결하기 위해 **15가지 프로덕션 패턴**을 적용한 구조입니다.
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
별도의 `@Scheduled` 릴레이가 미발행 이벤트를 폴링하여 **StreamBridge**로 전송합니다.

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
@SchedulerLock(name = "OutboxRelay", lockAtMostFor = "50s", lockAtLeastFor = "5s")
@Transactional
public void publishPendingEvents() {
    List<OutboxEvent> pendingEvents =
        outboxEventRepository.findByPublishedFalseOrderByCreatedAtAsc();

    for (OutboxEvent event : pendingEvents) {
        String binding = resolveBindingName(event.getEventType());
        Message<String> message = MessageBuilder.withPayload(event.getPayload())
            .setHeader("kafka_messageKey", event.getAggregateId())
            .build();
        boolean sent = streamBridge.send(binding, message);
        if (sent) event.markPublished();
    }
}
```

### 주의점
- **폴링 지연**: 최대 1초 지연 발생 (fixedDelay=1000). 실시간성이 중요하면 CDC(Change Data Capture) 고려
- **순서 보장**: 같은 aggregate 내에서만 순서 보장됨. 서로 다른 aggregate 간 순서는 보장 불가
- **중복 발행 가능**: Kafka 전송 성공 후 DB 업데이트 전 서버 다운 시 → Consumer 측 멱등성 필수
- **OutboxEvent 정리**: published=true인 이벤트를 주기적으로 삭제하는 배치 필요

---

## 2. Spring Cloud Stream Consumer + DLQ (Dead Letter Queue)

### 문제
`@KafkaListener`에서 처리 실패 시 메시지가 유실됩니다.
또한 Kafka에 직접 의존하면 브로커 교체 시 모든 코드를 수정해야 합니다.

### 해결
Spring Cloud Stream의 **함수형 Consumer**로 메시지를 소비하고, **바인더 레벨 DLQ**로 실패 메시지를 처리합니다.
`@Transactional` 로직은 별도 `@Service` Handler 클래스로 분리합니다 (Spring AOP 프록시 이슈 방지).

```java
// msa-traffic/payment-service/.../event/OrderEventListener.java
@Configuration
public class OrderEventListener {
    @Bean
    public Consumer<Message<String>> handleOrderCreated(OrderEventHandler handler) {
        return message -> {
            OrderCreatedEvent event = objectMapper.readValue(
                message.getPayload(), OrderCreatedEvent.class);
            handler.handle(event);  // @Transactional 처리는 Handler에 위임
        };
    }
}

// msa-traffic/payment-service/.../event/OrderEventHandler.java
@Service
public class OrderEventHandler {
    @Transactional
    public void handle(OrderCreatedEvent event) {
        if (processedEventRepository.existsById(event.eventId())) return;
        // 결제 처리 + ProcessedEvent 저장 + Outbox 이벤트 발행
    }
}
```

### 재시도 + DLQ 설정 (application.yml)
```yaml
spring.cloud.stream:
  bindings:
    handleOrderCreated-in-0:
      destination: order-events
      group: payment-service
      consumer:
        max-attempts: 4                    # 4회 재시도
        back-off-initial-interval: 1000    # 1초 → 2초 → 4초 (exponential)
        back-off-multiplier: 2.0
  kafka.bindings:
    handleOrderCreated-in-0:
      consumer:
        enableDlq: true                    # ★ 바인더 레벨 DLQ
        dlqName: order-events.payment-service.dlq
```

### 재시도 흐름
```
order-events → 1차 실패 (1초 후)
  → 2차 실패 (2초 후)
    → 3차 실패 (4초 후)
      → 4차 실패
        → order-events.payment-service.dlq (DLQ 토픽)
```

### 주의점
- **DLQ 모니터링 필수**: DLQ에 쌓인 메시지를 수동으로 처리하는 운영 프로세스 필요
- **AOP 프록시**: 함수형 빈의 람다에서는 `@Transactional`이 동작하지 않음 → Handler 분리 필수
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

## Kafka Producer 보장 수준 (Spring Cloud Stream)

```yaml
# msa-traffic/order-service/src/main/resources/application.yml
spring.cloud.stream:
  kafka:
    binder:
      brokers: ${KAFKA_BROKERS:localhost:9092}
      producerProperties:
        acks: all                      # 모든 replica 확인
        retries: 3                     # 전송 실패 시 재시도
        enable.idempotence: true       # 멱등한 프로듀서
      consumerProperties:
        enable.auto.commit: false      # 수동 커밋
```

---

## 코드 위치 가이드

| 패턴 | 파일 경로 |
|------|---------|
| Outbox 엔티티 | `msa-traffic/common/common-outbox/.../OutboxEvent.java` |
| Outbox 저장 | `msa-traffic/common/common-outbox/.../OutboxService.java` |
| Outbox 릴레이 | `msa-traffic/common/common-outbox/.../OutboxRelay.java` |
| 함수형 Consumer + Handler (Payment) | `msa-traffic/payment-service/.../event/OrderEventListener.java` + `OrderEventHandler.java` |
| 함수형 Consumer + Handler (Order) | `msa-traffic/order-service/.../event/PaymentEventListener.java` + `PaymentEventHandler.java` |
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
| Spring Cloud Stream | `msa-traffic/common/common-outbox/.../OutboxRelay.java` |
| 함수형 Consumer (Payment) | `msa-traffic/payment-service/.../event/OrderEventListener.java` |
| 함수형 Consumer (Order) | `msa-traffic/order-service/.../event/PaymentEventListener.java` |
| 함수형 Consumer (Delivery) | `msa-traffic/delivery-service/.../event/PaymentEventListener.java` |
| Redis 주문 대기열 | `msa-traffic/order-service/.../service/OrderQueueService.java` |
| 대기열 프로세서 | `msa-traffic/order-service/.../scheduler/OrderQueueProcessor.java` |
| Redis Pub/Sub 발행 | `msa-traffic/order-service/.../event/OrderStatusPublisher.java` |
| Redis Pub/Sub 구독 | `msa-traffic/order-service/.../event/OrderStatusSubscriber.java` |
| GCP Pub/Sub 프로필 | `msa-traffic/*/src/main/resources/application-gcp.yml` |

---

## 13. Spring Cloud Stream - 브로커 추상화

### 문제
Kafka를 직접 사용(`KafkaTemplate`, `@KafkaListener`)하면 브로커 교체 시 모든 코드를 수정해야 합니다.
클라우드 배포 시 Kafka 클러스터 운영 비용이 매월 $200+ 발생합니다.

### 해결
Spring Cloud Stream으로 메시징을 추상화하여, **코드 변경 0줄**로 브로커를 교체합니다.

```java
// Before: Kafka 직접 의존
KafkaTemplate<String, String> kafkaTemplate;
kafkaTemplate.send(topic, key, payload);

// After: StreamBridge 추상화
StreamBridge streamBridge;
streamBridge.send(bindingName, message);
// application.yml의 binder만 변경하면 Kafka → GCP Pub/Sub 전환 완료
```

### 비용 비교

| 브로커 | 월 비용 | 특징 |
|--------|---------|------|
| Kafka (AWS MSK) | ~$200+ | 2개 브로커 최소, 상시 운영 |
| GCP Pub/Sub | ~$3-10 | 메시지당 과금, 서버리스 |
| Redis Queue | $0 | 이미 Redis 사용 중 |

---

## 14. Redis 주문 대기열 (Sorted Set)

### 문제
피크타임(점심/저녁)에 주문이 폭주하면 시스템 과부하가 발생합니다.

### 해결
Redis Sorted Set으로 주문을 대기열에 넣고 순서대로 처리합니다 (티켓팅 대기열 패턴).

```java
// 대기열 추가: ZADD order:queue {timestamp} {orderId}
void enqueue(Long orderId);

// 대기열에서 꺼내기: ZPOPMIN order:queue
Long dequeue();

// 현재 순번 조회: ZRANK order:queue {orderId} → O(log N)
long getPosition(Long orderId);
```

### 3단계 트래픽 제어

```
1단계: Gateway Rate Limiting (Redis Token Bucket) → 전체 요청 속도 제한
2단계: @RateLimiter (Resilience4j) → 서비스 레벨 요청 속도 제한
3단계: Redis Queue (Sorted Set) → 피크타임 주문 대기열
```

---

## 15. Redis Pub/Sub - 실시간 알림

### 문제
주문 상태가 변경될 때 클라이언트에게 즉시 알려줄 방법이 없습니다.

### 해결
Redis Pub/Sub로 상태 변경을 실시간 브로드캐스트합니다.

```java
// Publisher
redisTemplate.convertAndSend("order:status", message);

// Subscriber (MessageListener)
public void onMessage(Message message, byte[] pattern) {
    // WebSocket/SSE로 클라이언트에게 전달
}
```

### 메시징 3종 비교

| 패턴 | 도구 | 영속성 | 용도 |
|------|------|--------|------|
| Saga 이벤트 | Kafka (Spring Cloud Stream) | O (로그 기반) | 서비스 간 신뢰성 높은 비동기 통신 |
| 주문 대기열 | Redis Sorted Set | O (소비까지) | 피크타임 주문 폭주 관리 |
| 실시간 알림 | Redis Pub/Sub | X (fire-and-forget) | 주문 상태 변경 즉시 알림 |
