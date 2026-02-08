# GoEats - Monolithic vs MSA vs MSA-Traffic 비교 레포지토리

배달 서비스(GoEats)의 **주문 흐름(Order -> Payment -> Delivery)**을 동일한 도메인으로 구현하여,
Monolithic → Basic MSA → Traffic MSA 아키텍처의 코드 구조 및 패턴 차이를 실제 코드로 비교합니다.

## Tech Stack

| 구분 | Monolithic | MSA (Basic) | MSA-Traffic (Production) |
|------|-----------|-------------|--------------------------|
| Framework | Spring Boot 3.2.2 | + Spring Cloud 2023.0.0 | + Spring Cloud Gateway |
| Database | H2 (단일 DB) | H2 (서비스별 독립 DB) | + HikariCP 튜닝 |
| Cache | Caffeine (로컬) | Redis (분산) | + Cache Warming + 다단계 Fallback |
| Messaging | ApplicationEventPublisher | Apache Kafka | + Transactional Outbox + DLQ |
| Communication | 직접 메서드 호출 | OpenFeign (HTTP) | + 계단식 타임아웃 |
| Resilience | try-catch | Circuit Breaker | + Retry + Bulkhead + RateLimiter + TimeLimiter |
| Lock | JPA @Lock (DB) | Redisson (분산 락) | + Fencing Token |
| Gateway | - | - | Spring Cloud Gateway (라우팅/인증/제한) |
| Monitoring | - | - | Actuator + Prometheus |
| Auth | - | JWT (공통 모듈) | + Gateway JWT 검증 + X-User-Id 전파 |

---

## 디렉토리 구조

```
GoEats/
├── monolithic/                    # Monolithic 애플리케이션
│   ├── build.gradle
│   └── src/main/java/com/goeats/
│       ├── GoEatsApplication.java # 단일 진입점
│       ├── common/                # config, exception, dto
│       ├── user/                  # 사용자 도메인
│       ├── store/                 # 가게/메뉴 도메인
│       ├── order/                 # ★ 주문 (핵심 비교 대상)
│       ├── payment/               # 결제 도메인
│       └── delivery/              # 배달 도메인
│
├── msa/                           # MSA (Basic) 멀티 모듈
│   ├── common/
│   │   ├── common-dto/            # Kafka 이벤트 스키마
│   │   ├── common-security/       # JWT 인증 필터
│   │   └── common-exception/      # 공통 예외 처리
│   ├── order-service/             # :8081 - 주문 서비스
│   ├── store-service/             # :8082 - 가게 서비스
│   ├── payment-service/           # :8083 - 결제 서비스
│   └── delivery-service/          # :8084 - 배달 서비스
│
├── msa-traffic/                   # ★ MSA-Traffic (Production) 멀티 모듈
│   ├── common/
│   │   ├── common-dto/            # ★ 모든 이벤트에 eventId 추가
│   │   ├── common-security/       # JWT (Gateway 연동)
│   │   ├── common-exception/      # ★ Resilience4j 예외 핸들러
│   │   ├── common-outbox/         # ★ Transactional Outbox
│   │   └── common-resilience/     # ★ Resilience4j + Prometheus
│   ├── gateway-service/           # ★ :8080 - API Gateway
│   ├── order-service/             # :8081 - ★ Outbox, Saga State, 멱등성
│   ├── store-service/             # :8082 - ★ Cache Warming, 다단계 Fallback
│   ├── payment-service/           # :8083 - ★ 멱등 컨슈머, DLQ, Outbox
│   └── delivery-service/          # :8084 - ★ Fencing Token, Bulkhead, ShedLock
│
└── README.md
```

---

## ★ 3-Way 핵심 패턴 비교

### 이벤트 발행

| Monolithic | MSA (Basic) | MSA-Traffic |
|-----------|-------------|-------------|
| `eventPublisher.publishEvent()` | `kafkaTemplate.send()` | `outboxService.saveEvent()` |
| JVM 내부 동기 이벤트 | Kafka 비동기 → 트랜잭션 밖에서 발행 | **★ 트랜잭션 내부에서 Outbox에 저장** |
| 유실 없음 (같은 JVM) | DB 커밋 후 Kafka 실패 → 이벤트 유실 | @Scheduled 릴레이가 Kafka로 전송 |

```java
// Monolithic
eventPublisher.publishEvent(new PaymentCompletedEvent(order));

// MSA Basic - 이벤트 유실 가능
@Transactional
public Order createOrder(...) {
    orderRepository.save(order);       // 1. DB 커밋
    kafkaTemplate.send("order-events", event);  // 2. ← 여기서 실패하면 이벤트 유실!
}

// MSA Traffic - Transactional Outbox (원자성 보장)
@Transactional
public Order createOrder(...) {
    orderRepository.save(order);       // 1. DB 저장
    outboxService.saveEvent("Order",   // 2. 같은 트랜잭션으로 Outbox 저장
        order.getId().toString(), "OrderCreated", event);
    // → @Scheduled OutboxRelay가 Kafka로 전송 (별도 트랜잭션)
}
```

### 이벤트 수신

| Monolithic | MSA (Basic) | MSA-Traffic |
|-----------|-------------|-------------|
| `@EventListener` | `@KafkaListener` | `@RetryableTopic` + `@DltHandler` |
| 실패 시 예외 전파 | 실패 시 메시지 유실 | **★ 4회 재시도 → DLT 이동** |
| - | 중복 처리 가능성 | **★ ProcessedEvent로 멱등성 보장** |

```java
// MSA Basic - 실패 시 메시지 유실
@KafkaListener(topics = "payment-events")
public void handlePaymentCompleted(PaymentCompletedEvent event) {
    // 처리 실패하면 메시지 유실됨
}

// MSA Traffic - 재시도 + DLQ + 멱등성
@RetryableTopic(attempts = "4", backoff = @Backoff(delay = 1000, multiplier = 2.0))
@KafkaListener(topics = "payment-events")
public void handlePaymentCompleted(PaymentCompletedEvent event) {
    if (processedEventRepository.existsById(event.eventId())) {
        return; // ★ 중복 이벤트 스킵
    }
    // 처리 로직...
    processedEventRepository.save(new ProcessedEvent(event.eventId()));
}

@DltHandler  // ★ 4회 실패 후 Dead Letter Topic으로 이동
public void handleDlt(Object event) {
    log.error("Manual intervention required: {}", event);
}
```

### 장애 복원력 (Resilience)

| 패턴 | Monolithic | MSA (Basic) | MSA-Traffic |
|------|-----------|-------------|-------------|
| Circuit Breaker | - | `@CircuitBreaker` | `@CircuitBreaker` |
| Retry | try-catch 재시도 | - | `@Retry(maxAttempts=3)` |
| Bulkhead | - | - | `@Bulkhead(maxConcurrent=20)` |
| Rate Limiter | - | - | `@RateLimiter(limitForPeriod=50)` |
| Time Limiter | - | - | `@TimeLimiter(timeout=5s)` |
| Gateway Rate Limit | - | - | Redis Token Bucket |
| Cascading Timeout | - | - | Gateway(8s) > Service(5s) > Feign(3s) |

```java
// MSA Basic - Circuit Breaker만
@CircuitBreaker(name = "storeService", fallbackMethod = "fallback")
public Order createOrder(...) { ... }

// MSA Traffic - 전체 Resilience 스택
@Retry(name = "storeService")           // 1차: 재시도
@CircuitBreaker(name = "storeService")   // 2차: 회로 차단
@Bulkhead(name = "orderCreation")        // 3차: 동시 요청 제한
public Order createOrder(...) { ... }
```

### 분산 락

| Monolithic | MSA (Basic) | MSA-Traffic |
|-----------|-------------|-------------|
| `@Lock(PESSIMISTIC_WRITE)` | Redisson `RLock` | Redisson + **★ Fencing Token** |
| DB 레벨 락 | Redis 분산 락 | 분산 락 + Stale 락 방지 |

```java
// MSA Basic - 락 만료 후 stale write 가능
RLock lock = redissonClient.getLock(lockKey);
lock.tryLock(5, 3, TimeUnit.SECONDS);
delivery.assignRider(riderName, riderPhone);  // ← 락 만료 후에도 실행 가능!

// MSA Traffic - Fencing Token으로 stale write 방지
RAtomicLong counter = redissonClient.getAtomicLong("fencing:" + orderId);
long fencingToken = counter.incrementAndGet();  // 단조 증가 토큰
// DB에서 token > 기존값일 때만 업데이트
int updated = deliveryRepository.updateWithFencingToken(
    id, riderName, riderPhone, fencingToken);
if (updated == 0) throw new BusinessException(STALE_LOCK_DETECTED);
```

### 캐시 전략

| Monolithic | MSA (Basic) | MSA-Traffic |
|-----------|-------------|-------------|
| Caffeine `@Cacheable` | Redis `@Cacheable` | Redis + **★ 다단계 Fallback** |
| - | - | **★ Cache Warming (서버 시작 시 프리로드)** |
| - | - | **★ 캐시별 TTL 설정** |

```java
// MSA Traffic - 3단계 Fallback
@Cacheable(value = "stores", key = "#id")        // Level 1: Redis 캐시
@CircuitBreaker(name = "storeDb", fallbackMethod = "getStoreFallback")
public Store getStore(Long id) {
    return storeRepository.findById(id);           // Level 2: DB 조회
}

private Store getStoreFallback(Long id, Throwable t) {
    Object cached = redisTemplate.opsForValue().get("stores::" + id);
    if (cached instanceof Store store) return store; // Level 3: 수동 Redis
    throw new BusinessException(SERVICE_UNAVAILABLE); // Level 4: 에러
}
```

---

## 주문 흐름 비교

### Monolithic: 단일 트랜잭션

```
Client ─── POST /api/orders ───> OrderController
                                      │
                                      ▼
                              ┌─ @Transactional ──────────────────────────┐
                              │  1. userService.getUser()      (메서드 호출) │
                              │  2. storeService.getStore()    (메서드 호출) │
                              │  3. orderRepository.save()     (같은 DB)    │
                              │  4. paymentService.process()   (메서드 호출) │
                              │  5. deliveryService.create()   (메서드 호출) │
                              │  실패 시 → 전체 자동 롤백                    │
                              └───────────────────────────────────────────┘
```

### MSA-Traffic: Saga + Outbox + Resilience

```
Client ── POST /api/orders ──> Gateway (:8080)
                                  │
                   ┌──────────────┤ JWT 검증 + Rate Limiting
                   │              │ + Circuit Breaker
                   ▼              │
            OrderController (:8081)
                   │
    ┌──────────────┤ Idempotency-Key 중복 체크 (Redis)
    │              │
    │   ┌──────────┴──────────────────────────────────┐
    │   │ @Transactional (원자적 처리)                    │
    │   │                                              │
    │   │  1. StoreServiceClient → store-service       │
    │   │     (@Retry + @CircuitBreaker + @Bulkhead)   │
    │   │  2. Order 엔티티 저장 (order_db)                │
    │   │  3. SagaState 생성 (상태 추적)                   │
    │   │  4. ★ OutboxEvent 저장 (같은 트랜잭션)           │
    │   └──────────────────────────────────────────────┘
                   │
    ┌──────────────┤ @Scheduled OutboxRelay (1초 간격)
    │              │ Outbox → Kafka 발행
    │              ▼
    │   ┌─── Kafka: order-events ──────────────────────┐
    │   │                                              │
    │   ▼                                              │
    │  payment-service (:8083)                         │
    │  │ @RetryableTopic (4회 재시도)                     │
    │  │ ProcessedEvent 중복 체크                         │
    │  │ 결제 처리 (idempotencyKey 중복 방지)               │
    │  │ ★ Outbox로 결과 이벤트 발행                       │
    │  │                                               │
    │  ├─ 성공 → Kafka: payment-events                  │
    │  └─ 실패 → Kafka: payment-failed-events           │
    │              │                │
    │              ▼                ▼
    │   ┌──────────────┐  ┌──────────────┐
    │   │ order-service │  │ delivery-svc │
    │   │ 상태 → PAID    │  │ 배달 생성      │
    │   │ Saga → DONE   │  │ Fencing Token │
    │   │ ProcessedEvent│  │ @Bulkhead    │
    │   └──────────────┘  │ ShedLock     │
    │                     └──────────────┘
    │
    └── 실패 시: @DltHandler → Dead Letter Topic → 수동 처리
```

---

## 트래픽 시나리오별 3-Way 비교

### 주문 폭주 (점심 피크 타임)

| | Monolithic | MSA Basic | MSA-Traffic |
|---|-----------|-----------|-------------|
| 스케일링 | 전체 복제 | 서비스별 스케일 아웃 | + Gateway 라우팅 |
| 병목 | 단일 DB 커넥션 풀 | 서비스별 독립 DB | + HikariCP 튜닝 |
| 과부하 보호 | - | - | **★ Rate Limiter (50/s)** |
| 스레드 격리 | - | - | **★ Bulkhead (20 concurrent)** |

### 결제 서비스 장애

| | Monolithic | MSA Basic | MSA-Traffic |
|---|-----------|-----------|-------------|
| 주문 접수 | 전체 실패 | 접수 가능, 결제 지연 | 접수 가능, 결제 지연 |
| 이벤트 보존 | - | Kafka offset 보존 | **★ Outbox + DLQ** |
| 중복 처리 | - | 가능성 있음 | **★ 멱등성 보장** |
| 모니터링 | - | - | **★ Prometheus 메트릭** |

### 이중 결제 방지

| | Monolithic | MSA Basic | MSA-Traffic |
|---|-----------|-----------|-------------|
| 보호 방식 | DB 트랜잭션 | `existsByOrderId()` | + **★ idempotencyKey** |
| Kafka 중복 | 해당 없음 | 처리 불가 | **★ ProcessedEvent** |
| API 중복 | 해당 없음 | 처리 불가 | **★ Idempotency-Key 헤더** |

### 배달 기사 동시 매칭

| | Monolithic | MSA Basic | MSA-Traffic |
|---|-----------|-----------|-------------|
| 락 방식 | DB 비관적 락 | Redisson 분산 락 | + **★ Fencing Token** |
| Stale 락 | DB가 보호 | 가능성 있음 | **★ 토큰으로 stale write 차단** |
| 동시성 | DB 부하 집중 | Redis 분산 | + **★ @Bulkhead** |
| 다중 인스턴스 | - | - | **★ ShedLock** |

---

## 패턴별 코드 위치 가이드

| 패턴 | Monolithic | MSA Basic | MSA-Traffic |
|------|-----------|-----------|-------------|
| **주문 생성** | `monolithic/.../order/service/OrderService.java` | `msa/order-service/.../service/OrderService.java` | `msa-traffic/order-service/.../service/OrderService.java` |
| **이벤트 발행** | (ApplicationEventPublisher) | `msa/.../service/OrderEventPublisher.java` | `msa-traffic/common/common-outbox/.../OutboxService.java` |
| **이벤트 수신** | (@EventListener) | `msa/.../event/PaymentEventListener.java` | `msa-traffic/.../event/PaymentEventListener.java` (@RetryableTopic) |
| **Outbox Relay** | - | - | `msa-traffic/common/common-outbox/.../OutboxRelay.java` |
| **Saga 추적** | - | - | `msa-traffic/order-service/.../entity/SagaState.java` |
| **멱등 컨슈머** | - | - | `msa-traffic/*/event/ProcessedEvent.java` |
| **API Gateway** | - | - | `msa-traffic/gateway-service/` |
| **Rate Limiting** | - | - | `msa-traffic/store-service/.../controller/StoreController.java` |
| **Fencing Token** | - | - | `msa-traffic/delivery-service/.../repository/DeliveryRepository.java` |
| **Cache Warming** | - | - | `msa-traffic/store-service/.../config/CacheWarmingRunner.java` |
| **ShedLock** | - | - | `msa-traffic/delivery-service/.../config/ShedLockConfig.java` |
| **Resilience 설정** | - | `msa/order-service/.../application.yml` | `msa-traffic/order-service/.../application.yml` (5패턴) |

---

## 아키텍처 진화 단계

```
[Monolithic]          [MSA Basic]              [MSA Traffic]
단순하지만 한계       분산했지만 취약             프로덕션 수준

 단일 DB              서비스별 DB               서비스별 DB
 단일 트랜잭션          Saga 패턴                + Saga State 추적
 직접 호출             OpenFeign                + 계단식 타임아웃
 try-catch            Circuit Breaker          + Retry + Bulkhead + RateLimiter
 Caffeine             Redis Cache              + Cache Warming + 다단계 Fallback
 DB Lock              Redisson Lock            + Fencing Token + ShedLock
 -                    kafkaTemplate.send()     + Transactional Outbox
 -                    @KafkaListener           + @RetryableTopic + DLQ
 -                    -                        + API Gateway + Rate Limiting
 -                    -                        + Prometheus 모니터링
```

---

## 언제 무엇을 선택할까?

### Monolithic이 적합한 경우
- 팀 규모가 작고 (1~5명) 빠른 개발이 필요할 때
- 트래픽이 예측 가능하고 급격한 스케일링이 불필요할 때
- 강한 데이터 일관성이 중요할 때

### MSA (Basic)이 적합한 경우
- 팀이 크고 (10명+) 도메인별 독립 배포가 필요할 때
- 특정 서비스 장애가 전체에 영향을 주면 안 될 때
- MSA 패턴을 학습하고 적용하는 초기 단계

### MSA-Traffic (Production)이 적합한 경우
- 대규모 트래픽을 처리해야 할 때 (피크 타임 대응)
- 이벤트 유실이 허용되지 않을 때 (Outbox + DLQ)
- 이중 결제 등 중복 처리 방지가 필수일 때 (멱등성)
- 서비스 간 장애 전파를 완전히 차단해야 할 때 (Bulkhead)
- 운영 모니터링이 필요할 때 (Prometheus)

---

## 참고

- 이 레포지토리는 **교육 목적**으로 작성되었습니다
- 3가지 아키텍처를 나란히 비교하여 각 패턴의 필요성과 트레이드오프를 학습할 수 있습니다
- MSA-Traffic의 패턴들은 실제 프로덕션 환경에서 발생하는 문제들을 해결하기 위해 설계되었습니다
