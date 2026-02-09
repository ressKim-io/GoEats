# 아키텍처 진화 개요

GoEats 프로젝트는 동일한 배달 서비스 도메인을 **3가지 아키텍처**로 구현하여 각 단계의 트레이드오프를 비교합니다.

---

## 진화 흐름

```
[Monolithic]              [MSA Basic]                [MSA Traffic]
  단순하지만 한계             분산했지만 취약              프로덕션 수준

  단일 DB                  서비스별 DB                 서비스별 DB
  단일 트랜잭션               Saga 패턴                 + Saga State 추적
  직접 호출                  OpenFeign                 + 계단식 타임아웃
  try-catch                Circuit Breaker            + Retry + Bulkhead + RateLimiter
  Caffeine                 Redis Cache                + Cache Warming + 다단계 Fallback
  DB Lock                  Redisson Lock              + Fencing Token + ShedLock
  -                        kafkaTemplate.send()       + Transactional Outbox
  -                        @KafkaListener             + Spring Cloud Stream + DLQ
  -                        -                          + API Gateway + Rate Limiting
  -                        -                          + Redis 주문 대기열 (Sorted Set)
  -                        -                          + Redis Pub/Sub (실시간 알림)
  -                        -                          + Prometheus 모니터링
```

---

## 각 단계에서 해결한 문제와 새로 발생한 문제

### Monolithic → MSA Basic

**해결한 문제:**
- 독립 배포 불가 → 서비스별 독립 배포 가능
- 단일 장애점 → 서비스별 장애 격리
- 스케일링 비효율 → 서비스별 독립 스케일링
- DB 병목 → 서비스별 독립 DB

**새로 발생한 문제:**
- 분산 트랜잭션 관리 (Saga 필요)
- 서비스 간 통신 장애 (Circuit Breaker 필요)
- 분산 락 필요 (Redis)
- 이벤트 기반 비동기 통신의 복잡성

### MSA Basic → MSA Traffic

**해결한 문제:**
- `kafkaTemplate.send()` 실패 시 이벤트 유실 → **Transactional Outbox**로 원자성 보장
- Kafka 메시지 처리 실패 시 유실 → **Spring Cloud Stream + DLQ**로 재시도 및 Dead Letter 처리
- 브로커 교체 시 코드 수정 필수 → **Spring Cloud Stream** 추상화 (코드 변경 0줄로 브로커 교체)
- 중복 이벤트 처리 → **Idempotent Consumer** (ProcessedEvent + eventId)
- 과부하 시 연쇄 장애 → **Bulkhead + RateLimiter**로 격리
- 피크타임 주문 폭주 → **Redis Sorted Set 주문 대기열**로 순차 처리
- 분산 락 만료 후 stale write → **Fencing Token**으로 방지
- 콜드 스타트 시 캐시 미스 → **Cache Warming**으로 프리로드
- 주문 상태 변경 시 실시간 알림 불가 → **Redis Pub/Sub**로 즉시 브로드캐스트
- Saga 진행 상태 추적 불가 → **SagaState** 엔티티로 상태 머신 관리
- 다중 인스턴스 스케줄러 중복 실행 → **ShedLock**으로 방지
- 운영 가시성 부재 → **Prometheus + Actuator** 메트릭

**새로 발생한 문제 / 운영 고려사항:**
- Outbox 폴링 지연 (최대 1초)
- DLQ 메시지 수동 처리 프로세스 필요
- 멱등성 키 관리 오버헤드
- Redis Pub/Sub은 fire-and-forget (구독자 없으면 메시지 유실)
- 인프라 복잡성 증가 (Redis, Kafka, Prometheus)

---

## 기술 스택 비교

| 구분 | Monolithic | MSA Basic | MSA Traffic |
|------|-----------|-----------|-------------|
| Framework | Spring Boot 3.2.2 | + Spring Cloud 2023.0.0 | + Spring Cloud Gateway |
| Database | H2 (단일 DB) | H2 (서비스별 독립 DB) | + HikariCP 튜닝 |
| Cache | Caffeine (로컬) | Redis (분산) | + Cache Warming + 다단계 Fallback |
| Messaging | ApplicationEventPublisher | Apache Kafka | + **Spring Cloud Stream** + Outbox + DLQ |
| Queue | - | - | + **Redis Sorted Set** (주문 대기열) |
| Realtime | - | - | + **Redis Pub/Sub** (상태 알림) |
| Communication | 직접 메서드 호출 | OpenFeign (HTTP) | + 계단식 타임아웃 |
| Resilience | try-catch | Circuit Breaker | + Retry + Bulkhead + RateLimiter + TimeLimiter |
| Lock | JPA @Lock (DB) | Redisson (분산 락) | + Fencing Token |
| Gateway | - | - | Spring Cloud Gateway |
| Monitoring | - | - | Actuator + Prometheus |

---

## 언제 무엇을 선택할까?

### Monolithic이 적합한 경우
- 팀 규모가 작고 (1~5명) 빠른 개발이 필요할 때
- 트래픽이 예측 가능하고 급격한 스케일링이 불필요할 때
- 강한 데이터 일관성이 중요할 때

### MSA Basic이 적합한 경우
- 팀이 크고 (10명+) 도메인별 독립 배포가 필요할 때
- 특정 서비스 장애가 전체에 영향을 주면 안 될 때
- MSA 패턴을 학습하고 적용하는 초기 단계

### MSA Traffic이 적합한 경우
- 대규모 트래픽을 처리해야 할 때 (피크 타임 대응)
- 이벤트 유실이 허용되지 않을 때 (Outbox + DLQ)
- 이중 결제 등 중복 처리 방지가 필수일 때 (멱등성)
- 서비스 간 장애 전파를 완전히 차단해야 할 때 (Bulkhead)
- 운영 모니터링이 필요할 때 (Prometheus)

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

### MSA Traffic: Saga + Outbox + Resilience

```
Client ── POST /api/orders ──> Gateway (:8080)
                                  │
                   ┌──────────────┤ JWT 검증 + Rate Limiting + Circuit Breaker
                   ▼
            OrderController (:8081)
                   │
    ┌──────────────┤ Idempotency-Key 중복 체크 (Redis)
    │   ┌──────────┴──────────────────────────────────┐
    │   │ @Transactional (원자적 처리)                    │
    │   │  1. StoreServiceClient (Feign + Retry + CB)  │
    │   │  2. Order 저장 (order_db)                     │
    │   │  3. SagaState 생성                            │
    │   │  4. OutboxEvent 저장 (같은 트랜잭션)            │
    │   └──────────────────────────────────────────────┘
                   │
    ┌──────────────┤ @Scheduled OutboxRelay (1초 간격)
    │              ▼
    │   ┌─── Kafka: order-events ─────────────────────┐
    │   ▼                                              │
    │  payment-service (:8083)                         │
    │  │ Spring Cloud Stream (4회 재시도)                  │
    │  │ ProcessedEvent 중복 체크                         │
    │  │ Outbox로 결과 이벤트 발행                         │
    │  ├─ 성공 → Kafka: payment-events                  │
    │  └─ 실패 → Kafka: payment-failed-events           │
    │              │                │
    │              ▼                ▼
    │   ┌──────────────┐  ┌──────────────┐
    │   │ order-service │  │ delivery-svc │
    │   │ 상태 → PAID    │  │ Fencing Token │
    │   │ Saga 완료      │  │ @Bulkhead    │
    │   └──────────────┘  └──────────────┘
    │
    └── 실패 시: DLQ (바인더 레벨) → Dead Letter Topic → 수동 처리
```

---

## 관련 문서

- [01-monolithic.md](./01-monolithic.md) - Monolithic 패턴 상세
- [02-msa-basic.md](./02-msa-basic.md) - MSA Basic 패턴과 한계
- [03-msa-traffic.md](./03-msa-traffic.md) - MSA Traffic 핵심 기술과 주의점
- [04-pattern-comparison.md](./04-pattern-comparison.md) - 3-Way 코드 비교
- [05-traffic-scenarios.md](./05-traffic-scenarios.md) - 트래픽 시나리오별 대응
- [06-setup-guide.md](./06-setup-guide.md) - 빌드 및 실행 가이드
