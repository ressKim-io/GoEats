# 트래픽 시나리오별 대응 전략

대규모 트래픽 환경에서 발생할 수 있는 장애 시나리오와 각 아키텍처의 대응 방식을 비교합니다.

---

## 시나리오 1: 주문 폭주 (점심 피크 타임)

평소 대비 10배 트래픽이 몰리는 상황.

| | Monolithic | MSA Basic | MSA Traffic |
|---|-----------|-----------|-------------|
| 스케일링 | 전체 복제 | 서비스별 스케일 아웃 | + Gateway 라우팅 |
| 병목 | 단일 DB 커넥션 풀 | 서비스별 독립 DB | + HikariCP 튜닝 |
| 과부하 보호 | - | - | **RateLimiter (50/s)** |
| 스레드 격리 | - | - | **Bulkhead (20 concurrent)** |
| 주문 대기열 | - | - | **Redis Sorted Set** |

### Monolithic 대응
- 서버 전체를 복제해야 함 (불필요한 리소스 낭비)
- DB 커넥션 풀이 모든 도메인에서 공유되어 병목 발생
- 주문만 폭주해도 가게 조회, 결제 등 모든 기능에 영향

### MSA Basic 대응
- order-service만 3대로 스케일 아웃 가능
- 각 서비스 DB가 독립적이라 병목 분산
- 하지만 과부하 보호 없이 서비스가 다운될 수 있음

### MSA Traffic 대응
```
Client → Gateway (Redis Rate Limit: 50/s 초과 → 429)
           ↓
  order-service (Bulkhead: 20개 초과 → 503)
           ↓                           ↓
  Redis Queue 활성?             실패 → 빠른 거부
    Yes → 대기열에 추가 (Sorted Set)
    No  → 즉시 처리 → Outbox 저장
           ↓
  OrderQueueProcessor (0.5초 간격) → 순차 처리
           ↓
  OutboxRelay → StreamBridge → Kafka → payment-service
```
- **3단계 트래픽 제어**: Gateway Rate Limit → Bulkhead → Redis Queue
- 대기열 활성 시 주문을 Redis Sorted Set에 넣고 순서대로 처리
- 대기 순번 조회 API(`GET /api/orders/queue/status`)로 사용자 안내
- 거부된 요청은 클라이언트가 재시도 (429 응답)

---

## 시나리오 2: 결제 서비스 장애

payment-service가 30초간 응답 불가한 상황.

| | Monolithic | MSA Basic | MSA Traffic |
|---|-----------|-----------|-------------|
| 주문 접수 | **전체 실패** | 접수 가능, 결제 지연 | 접수 가능, 결제 지연 |
| 이벤트 보존 | - | Kafka offset 보존 | **+ Outbox + 바인더 DLQ** |
| 중복 처리 | - | 가능성 있음 | **멱등성 보장** |
| 장애 감지 | 로그 확인 | 로그 확인 | **Prometheus 메트릭** |

### Monolithic 대응
- `@Transactional` 내에서 결제가 실패하면 주문도 롤백
- 결제 장애 = 전체 서비스 장애

### MSA Basic 대응
- 주문은 order_db에 저장되고, 이벤트는 Kafka에 보관
- payment-service 복구 후 Kafka에서 이벤트 소비
- 하지만 소비 실패 시 메시지 유실 가능

### MSA Traffic 대응
```
주문 → Order 저장 + Outbox 저장 (같은 TX)
         ↓
OutboxRelay → StreamBridge → Kafka: order-events
         ↓
payment-service (다운)
         ↓
Spring Cloud Stream Consumer: 1s → 2s → 4s → 8s (4회 재시도)
         ↓ (여전히 실패)
DLQ: order-events.payment-service.dlq (바인더 레벨 DLQ)
         ↓
운영팀 알림 → 수동 처리 또는 replay
```
- Outbox로 이벤트 유실 방지
- Spring Cloud Stream의 바인더 레벨 재시도 (maxAttempts + backOff)
- 최종 실패 시 DLQ에 보관 (데이터 유실 없음)
- Prometheus로 CB 상태, 재시도 횟수 실시간 모니터링

---

## 시나리오 3: 이중 결제 발생

네트워크 지연으로 클라이언트가 주문 API를 2번 호출하는 상황.

| | Monolithic | MSA Basic | MSA Traffic |
|---|-----------|-----------|-------------|
| API 중복 | DB 트랜잭션으로 방지 | 처리 불가 | **Idempotency-Key** |
| Kafka 중복 | 해당 없음 | 처리 불가 | **ProcessedEvent** |
| 결제 중복 | DB 트랜잭션 | `existsByOrderId()` | + **idempotencyKey** |

### Monolithic 대응
- 같은 트랜잭션 내에서 처리되므로 DB 제약조건으로 방지 가능

### MSA Basic 대응
- 2번의 HTTP 요청이 2개의 주문 생성 → 2개의 Kafka 이벤트 → 2번 결제
- `existsByOrderId()` 체크가 있어도 타이밍에 따라 중복 가능

### MSA Traffic 대응
```
요청 1: Idempotency-Key: "abc-123"
  → Redis SET NX "idempotency:order:abc-123" → true → 주문 생성

요청 2: Idempotency-Key: "abc-123" (같은 키)
  → Redis SET NX "idempotency:order:abc-123" → false → 409 DUPLICATE_REQUEST

만약 Kafka 이벤트가 중복 발행되더라도:
  payment-service:
    → processedEventRepository.existsById(eventId) → true → skip
```
- 3중 보호: API 레벨(Idempotency-Key) + Consumer 레벨(ProcessedEvent) + 비즈니스 레벨(idempotencyKey)

---

## 시나리오 4: 배달 기사 동시 매칭

여러 인스턴스에서 동시에 같은 주문에 기사를 매칭하는 상황.

| | Monolithic | MSA Basic | MSA Traffic |
|---|-----------|-----------|-------------|
| 락 방식 | DB 비관적 락 | Redisson 분산 락 | + **Fencing Token** |
| Stale 락 | DB가 보호 | 가능성 있음 | **토큰으로 차단** |
| 동시성 | DB 부하 집중 | Redis 분산 | + **@Bulkhead** |
| 다중 인스턴스 | - | - | **ShedLock** |

### MSA Basic의 문제
```
Thread A: 락 획득 (token 없음) → GC pause → 락 만료 3초
Thread B: 락 획득 → DB 쓰기 (기사 B 매칭) → 성공
Thread A: GC 재개 → DB 쓰기 (기사 A 매칭) → 성공 ← stale write!
결과: 기사 A로 덮어써짐 (기사 B 정보 유실)
```

### MSA Traffic의 해결
```
Thread A: fencingToken=5, 락 획득 → GC pause → 락 만료
Thread B: fencingToken=6, 락 획득 → DB UPDATE WHERE token < 6 → 성공
Thread A: GC 재개 → DB UPDATE WHERE token < 5 → 0 rows → 예외!
결과: 기사 B 매칭 유지 (stale write 차단)
```

---

## 시나리오 5: Kafka 브로커 장애

Kafka가 5분간 응답 불가한 상황.

| | Monolithic | MSA Basic | MSA Traffic |
|---|-----------|-----------|-------------|
| 영향 | 없음 (Kafka 미사용) | 이벤트 발행 실패 | **Outbox에 누적** |
| 복구 후 | - | 유실된 이벤트 수동 복구 | **자동 발행 재개** |

### MSA Basic 대응
- `kafkaTemplate.send()` 실패 → 이벤트 유실
- 복구 후 어떤 이벤트가 유실되었는지 추적 불가

### MSA Traffic 대응
```
Kafka 다운 중:
  주문 생성 → Order 저장 + OutboxEvent 저장 (정상)
  OutboxRelay → StreamBridge 전송 실패 → published=false 유지
  OutboxRelay → 1초 후 재시도 → 실패 → 반복...

Kafka 복구 후:
  OutboxRelay → published=false인 이벤트 순서대로 전송
  → 자동으로 밀린 이벤트 처리

★ 브로커 교체 시: application.yml의 binder만 변경
  Kafka → GCP Pub/Sub: 코드 변경 0줄 (Spring Cloud Stream 추상화)
```
- 주문 생성 자체는 정상 동작 (Outbox에 저장됨)
- Kafka 복구 후 자동으로 밀린 이벤트 순서대로 전송
- Spring Cloud Stream 덕분에 브로커를 GCP Pub/Sub 등으로 교체 가능 (코드 변경 없음)

---

## 시나리오 6: Redis 장애

Redis가 일시적으로 응답 불가한 상황.

| | Monolithic | MSA Basic | MSA Traffic |
|---|-----------|-----------|-------------|
| 캐시 | Caffeine (영향 없음) | Redis 캐시 미스 → DB 폭주 | **다단계 Fallback** |
| 분산 락 | DB 락 (영향 없음) | Redisson 락 실패 | Redisson 락 실패 |
| Rate Limiting | - | - | Gateway Rate Limit 비활성화 |

### MSA Traffic 대응
```
캐시 요청 → Redis 타임아웃
  → @Cacheable 미스 → DB 조회 시도
    → DB 정상 → 응답 (Redis 없이 동작)
    → DB도 과부하 → CircuitBreaker 발동
      → Fallback: 이전 캐시 확인 → 503 응답
```
- 다단계 Fallback으로 Redis 장애 시에도 서비스 유지
- Gateway Rate Limiting은 기본적으로 Redis 장애 시 allow (deny로 설정 가능)
- Fencing Token의 Redis AtomicLong도 사용 불가 → 기본 분산 락으로 fallback 필요

### 주의점
- Redis가 ShedLock, Rate Limiting, Cache, Fencing Token 등 여러 곳에서 사용됨
- Redis 장애 시 영향 범위가 넓으므로 Redis 고가용성(Sentinel/Cluster) 구성 권장

---

## 시나리오 대응 요약

| 시나리오 | Monolithic | MSA Basic | MSA Traffic |
|---------|:---------:|:---------:|:-----------:|
| 주문 폭주 | 전체 다운 | 부분 스케일 | Gateway + Bulkhead + RateLimiter + **Redis Queue** |
| 결제 장애 | 전체 실패 | 주문 가능, 이벤트 불안 | Outbox + **바인더 DLQ** + Prometheus |
| 이중 결제 | DB 보호 | 취약 | 3중 보호 (API + Consumer + 비즈니스) |
| 동시 매칭 | DB 락 | stale 위험 | Fencing Token + Bulkhead |
| Kafka 장애 | 무관 | 이벤트 유실 | Outbox 누적 + 자동 복구 + **브로커 교체 가능** |
| Redis 장애 | 무관 | 캐시 미스 | 다단계 Fallback |
