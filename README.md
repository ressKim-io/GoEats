# GoEats - Monolithic vs MSA 비교 레포지토리

배달 서비스(GoEats)의 **주문 흐름(Order -> Payment -> Delivery)**을 동일한 도메인으로 구현하여,
Monolithic과 MSA 아키텍처의 코드 구조 및 패턴 차이를 실제 코드로 비교합니다.

## Tech Stack

| 구분 | Monolithic | MSA |
|------|-----------|-----|
| Framework | Spring Boot 3.2.2 | Spring Boot 3.2.2 + Spring Cloud 2023.0.0 |
| Database | H2 (단일 DB) | H2 (서비스별 독립 DB) |
| Cache | Caffeine (로컬) | Redis (분산) |
| Messaging | ApplicationEventPublisher | Apache Kafka |
| Service Communication | 직접 메서드 호출 | OpenFeign (HTTP) |
| Resilience | try-catch | Resilience4j Circuit Breaker |
| Lock | JPA @Lock (DB) | Redisson (분산 락) |
| Auth | - | JWT (공통 모듈) |

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
├── msa/                           # MSA 멀티 모듈
│   ├── build.gradle               # 루트 빌드
│   ├── common/
│   │   ├── common-dto/            # Kafka 이벤트 스키마
│   │   ├── common-security/       # JWT 인증 필터
│   │   └── common-exception/      # 공통 예외 처리
│   ├── order-service/             # :8081 - 주문 서비스
│   ├── store-service/             # :8082 - 가게 서비스
│   ├── payment-service/           # :8083 - 결제 서비스
│   └── delivery-service/          # :8084 - 배달 서비스
│
└── README.md
```

---

## 주문 흐름 비교

### Monolithic: 단일 트랜잭션

```
Client ─── POST /api/orders ───> OrderController
                                      │
                                      ▼
                              ┌─ @Transactional ──────────────────────────┐
                              │                                           │
                              │  1. userService.getUser()      (메서드 호출) │
                              │  2. storeService.getStore()    (메서드 호출) │
                              │  3. orderRepository.save()     (같은 DB)    │
                              │  4. paymentService.process()   (메서드 호출) │
                              │  5. deliveryService.create()   (메서드 호출) │
                              │                                           │
                              │  실패 시 → 전체 자동 롤백                    │
                              └───────────────────────────────────────────┘
```

**특징**: 5개 단계가 하나의 `@Transactional`에서 실행. 어디서든 예외 발생 시 전체 롤백.

### MSA: Saga 패턴 (Choreography)

```
Client ── POST /api/orders ──> OrderController (:8081)
                                     │
                          ┌──────────┴──────────┐
                          │ 1. OpenFeign HTTP    │
                          │    → store-service   │
                          │    (Circuit Breaker) │
                          └──────────┬──────────┘
                                     │
                          ┌──────────┴──────────┐
                          │ 2. 로컬 트랜잭션       │
                          │    order_db에 저장     │
                          └──────────┬──────────┘
                                     │
                          ┌──────────┴──────────┐
                          │ 3. Kafka 발행         │
                          │    OrderCreatedEvent  │
                          └──────────┬──────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              ▼                      │                      │
   ┌─────────────────┐              │                      │
   │ payment-service  │              │                      │
   │ (:8083)          │              │                      │
   │                  │              │                      │
   │ 결제 처리          │              │                      │
   │ → 성공: Kafka 발행 │              │                      │
   │   PaymentCompleted│             │                      │
   │ → 실패: Kafka 발행 │              │                      │
   │   PaymentFailed   │             │                      │
   └────────┬─────────┘              │                      │
            │                        │                      │
            ▼                        ▼                      │
   ┌─────────────────┐    ┌─────────────────┐              │
   │ delivery-service │    │ order-service    │              │
   │ (:8084)          │    │ (:8081)          │              │
   │                  │    │                  │              │
   │ 배달 생성          │    │ 주문 상태 업데이트   │              │
   │ 라이더 매칭         │    │ (성공/실패 반영)    │              │
   │ (분산 락)          │    │                  │              │
   └──────────────────┘    └──────────────────┘              │
```

**특징**: 각 서비스가 독립 DB에 로컬 트랜잭션만 수행. 실패 시 보상 트랜잭션(saga compensation) 필요.

---

## 핵심 패턴 비교

### 1. 트랜잭션 관리

| | Monolithic | MSA |
|---|-----------|-----|
| 방식 | `@Transactional` (ACID) | Saga 패턴 (Eventually Consistent) |
| 롤백 | DB 자동 롤백 | 보상 트랜잭션 (이벤트 기반) |
| 복잡도 | 낮음 | 높음 |

```java
// Monolithic - 하나의 @Transactional로 끝
@Transactional
public Order createOrder(...) {
    orderRepository.save(order);
    paymentService.processPayment(order, paymentMethod);  // 같은 트랜잭션
    deliveryService.createDelivery(order);                 // 같은 트랜잭션
    return order;
}
```

```java
// MSA - 로컬 트랜잭션 + Kafka 이벤트
@Transactional
public Order createOrder(...) {
    order = orderRepository.save(order);                   // 로컬 트랜잭션만
    eventPublisher.publishOrderCreated(event);             // Kafka로 비동기 전달
    return order;  // 결제는 PaymentService에서 별도 처리
}
```

> 코드 위치:
> - `monolithic/.../order/service/OrderService.java` - `createOrder()` 메서드
> - `msa/order-service/.../service/OrderService.java` - `createOrder()` 메서드

---

### 2. 서비스 간 통신

| | Monolithic | MSA |
|---|-----------|-----|
| 동기 호출 | 직접 메서드 호출 | OpenFeign HTTP 클라이언트 |
| 비동기 호출 | `ApplicationEventPublisher` | Apache Kafka |
| 장애 전파 | 전체 영향 | Circuit Breaker로 격리 |

```java
// Monolithic - @Autowired 주입, 직접 호출
private final StoreService storeService;
Store store = storeService.getStoreWithMenus(storeId);  // 메서드 호출
```

```java
// MSA - OpenFeign HTTP 호출 + Circuit Breaker
@FeignClient(name = "store-service")
public interface StoreServiceClient {
    @GetMapping("/api/stores/{id}")
    StoreResponse getStore(@PathVariable Long id);       // HTTP GET 요청
}
```

> 코드 위치:
> - `monolithic/.../order/service/OrderService.java` - `userService`, `storeService` 필드
> - `msa/order-service/.../client/StoreServiceClient.java` - OpenFeign 인터페이스

---

### 3. 캐싱

| | Monolithic | MSA |
|---|-----------|-----|
| 방식 | Caffeine 로컬 캐시 | Redis 분산 캐시 |
| 범위 | 단일 JVM | 모든 서비스 인스턴스 공유 |
| 설정 | `spring.cache.type: caffeine` | `spring.data.redis` |

```yaml
# Monolithic - Caffeine 로컬 캐시
spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=10m
```

```yaml
# MSA - Redis 분산 캐시
spring:
  data:
    redis:
      host: localhost
      port: 6379
  cache:
    type: redis
    redis:
      time-to-live: 600000
```

> 코드 위치:
> - `monolithic/.../store/service/StoreService.java` - `@Cacheable` (Caffeine)
> - `msa/store-service/.../service/StoreService.java` - `@Cacheable` (Redis)

---

### 4. 동시성 제어 (락)

| | Monolithic | MSA |
|---|-----------|-----|
| 방식 | `@Lock(PESSIMISTIC_WRITE)` | Redisson 분산 락 |
| 범위 | 단일 DB | 다중 인스턴스 |
| 사용처 | JPA 쿼리 | 라이더 배정 |

```java
// Monolithic - JPA 비관적 락
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT m FROM Menu m WHERE m.id = :id")
Optional<Menu> findByIdWithLock(@Param("id") Long id);
```

```java
// MSA - Redisson 분산 락
RLock lock = redissonClient.getLock("lock:rider-assignment:" + orderId);
try {
    boolean acquired = lock.tryLock(5, 3, TimeUnit.SECONDS);
    if (acquired) {
        // 라이더 매칭 로직
    }
} finally {
    lock.unlock();
}
```

> 코드 위치:
> - `monolithic/.../store/repository/MenuRepository.java` - `@Lock` 쿼리
> - `msa/delivery-service/.../service/DeliveryService.java` - Redisson 분산 락

---

### 5. 장애 처리

| | Monolithic | MSA |
|---|-----------|-----|
| 방식 | try-catch + 롤백 | Circuit Breaker + Fallback |
| 장애 범위 | 전체 애플리케이션 | 서비스 단위 격리 |
| 복구 | 재시작 | 자동 복구 (half-open) |

```java
// Monolithic - 단순 try-catch
try {
    paymentService.processPayment(order, paymentMethod);
} catch (BusinessException e) {
    throw e;  // → @Transactional 자동 롤백
}
```

```java
// MSA - Circuit Breaker + Fallback
@CircuitBreaker(name = "storeService", fallbackMethod = "createOrderFallback")
public Order createOrder(...) {
    storeServiceClient.getStore(storeId);  // 장애 시 circuit open
}

private Order createOrderFallback(..., Throwable t) {
    throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
            "Store service is temporarily unavailable.");
}
```

> 코드 위치:
> - `monolithic/.../order/service/OrderService.java` - try-catch 블록
> - `msa/order-service/.../service/OrderService.java` - `@CircuitBreaker` + fallback

---

### 6. 데이터 모델링

| | Monolithic | MSA |
|---|-----------|-----|
| 관계 | JPA `@ManyToOne`, `@OneToMany` (FK) | ID만 저장 (FK 없음) |
| 조회 | SQL JOIN | HTTP API 호출 |
| DB | 단일 DB | 서비스별 독립 DB |

```java
// Monolithic - JPA 연관관계
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id")
private User user;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "store_id")
private Store store;
```

```java
// MSA - ID만 저장 (다른 서비스의 엔티티 직접 참조 불가)
@Column(nullable = false)
private Long userId;     // User 엔티티 참조 없음

@Column(nullable = false)
private Long storeId;    // Store 엔티티 참조 없음
```

> 코드 위치:
> - `monolithic/.../order/entity/Order.java` - `@ManyToOne User`, `@ManyToOne Store`
> - `msa/order-service/.../entity/Order.java` - `Long userId`, `Long storeId`

---

### 7. 이벤트 처리

| | Monolithic | MSA |
|---|-----------|-----|
| 방식 | `ApplicationEventPublisher` | Apache Kafka |
| 전달 보장 | 동기, 같은 JVM | 비동기, at-least-once |
| 재처리 | 불가 | 가능 (offset replay) |
| 스키마 | Java class (내부) | 공유 이벤트 모듈 (`common-dto`) |

> 코드 위치:
> - `monolithic/.../order/service/OrderService.java` - `eventPublisher.publishEvent()`
> - `msa/order-service/.../service/OrderEventPublisher.java` - `kafkaTemplate.send()`
> - `msa/payment-service/.../event/OrderEventListener.java` - `@KafkaListener`
> - `msa/common/common-dto/.../event/` - 이벤트 스키마 정의

---

## 패턴별 코드 위치 가이드

빠르게 비교하고 싶은 패턴을 찾아 아래 파일을 나란히 열어보세요.

| 패턴 | Monolithic 파일 | MSA 파일 |
|------|----------------|---------|
| **주문 생성 흐름** | `monolithic/.../order/service/OrderService.java` | `msa/order-service/.../service/OrderService.java` |
| **서비스 호출** | (같은 파일 내 직접 호출) | `msa/order-service/.../client/StoreServiceClient.java` |
| **이벤트 발행** | (ApplicationEventPublisher) | `msa/order-service/.../service/OrderEventPublisher.java` |
| **이벤트 수신** | (없음 - 동기 처리) | `msa/payment-service/.../event/OrderEventListener.java` |
| **캐시 설정** | `monolithic/src/main/resources/application.yml` | `msa/store-service/src/main/resources/application.yml` |
| **캐시 사용** | `monolithic/.../store/service/StoreService.java` | `msa/store-service/.../service/StoreService.java` |
| **동시성 제어** | `monolithic/.../store/repository/MenuRepository.java` | `msa/delivery-service/.../service/DeliveryService.java` |
| **엔티티 관계** | `monolithic/.../order/entity/Order.java` | `msa/order-service/.../entity/Order.java` |
| **에러 처리** | `monolithic/.../common/exception/GlobalExceptionHandler.java` | `msa/common/common-exception/.../GlobalExceptionHandler.java` |
| **테스트** | `monolithic/.../order/service/OrderServiceTest.java` | (Testcontainers 필요) |
| **DB 설정** | `monolithic/.../application.yml` (단일 DB) | 서비스별 `application.yml` (독립 DB) |

---

## 트래픽 시나리오별 비교

### 주문 폭주 (점심 피크 타임)

| | Monolithic | MSA |
|---|-----------|-----|
| 스케일링 | 전체 애플리케이션 복제 | 주문 서비스만 스케일 아웃 |
| 병목 | 단일 DB 커넥션 풀 | 서비스별 독립 DB |
| 영향 범위 | 전체 기능 느려짐 | 주문만 영향, 가게 조회는 정상 |

### 결제 서비스 장애

| | Monolithic | MSA |
|---|-----------|-----|
| 영향 | 주문 전체 실패 (같은 트랜잭션) | 주문 접수는 가능, 결제만 지연 |
| 복구 | 서비스 재시작 | Circuit Breaker 자동 복구 |
| 데이터 | 자동 롤백 (일관성 보장) | 보상 트랜잭션 필요 (eventual consistency) |

### 배달 기사 매칭 동시 요청

| | Monolithic | MSA |
|---|-----------|-----|
| 락 방식 | DB 비관적 락 (단일 인스턴스) | Redis 분산 락 (다중 인스턴스) |
| 성능 | DB 부하 집중 | Redis로 부하 분산 |
| 확장성 | 인스턴스 추가 시 락 경합 | 분산 락으로 안전하게 확장 |

---

## 언제 무엇을 선택할까?

### Monolithic이 적합한 경우
- 팀 규모가 작고 (1~5명) 빠른 개발이 필요할 때
- 트래픽이 예측 가능하고 급격한 스케일링이 불필요할 때
- 강한 데이터 일관성이 중요할 때 (금융, 결제 등)
- 운영 인프라가 제한적일 때

### MSA가 적합한 경우
- 팀이 크고 (10명+) 도메인별 독립 개발/배포가 필요할 때
- 트래픽 패턴이 도메인별로 다를 때 (주문은 피크, 가게 관리는 일정)
- 특정 서비스 장애가 전체에 영향을 주면 안 될 때
- Kafka, Redis, Kubernetes 등 인프라 운영 역량이 있을 때

---

## 참고

- 이 레포지토리는 **교육 목적**으로 작성되었습니다
- 실제 프로덕션에서는 API Gateway, Service Discovery, Config Server 등 추가 인프라가 필요합니다
- MSA의 Kafka 이벤트 흐름은 Transactional Outbox 패턴과 함께 사용하는 것을 권장합니다
