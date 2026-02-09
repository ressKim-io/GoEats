# GoEats - Monolithic vs MSA vs MSA-Traffic 비교 레포지토리

배달 서비스(GoEats)의 **주문 흐름(Order → Payment → Delivery)**을 동일한 도메인으로 구현하여,
Monolithic → Basic MSA → Traffic MSA 아키텍처의 코드 구조 및 패턴 차이를 실제 코드로 비교합니다.

---

## 디렉토리 구조

```
GoEats/
├── monolithic/          # 단일 Spring Boot 애플리케이션
├── msa/                 # MSA Basic (Saga, Circuit Breaker, 분산 락)
├── msa-traffic/         # MSA Traffic (Outbox, DLQ, Fencing Token, Gateway, ...)
└── docs/                # 상세 문서
```

## 기술 스택

| 구분 | Monolithic | MSA Basic | MSA Traffic |
|------|-----------|-----------|-------------|
| Framework | Spring Boot 3.2.2 | + Spring Cloud | + Gateway |
| DB | PostgreSQL 15 | + 서비스별 스키마 분리 | + 서비스별 스키마 분리 |
| Cache | Caffeine (로컬) | Redis (분산) | + Cache Warming + Fallback |
| Messaging | EventPublisher | Kafka (직접) | + **Spring Cloud Stream** + Outbox + DLQ |
| Queue | - | - | + **Redis Sorted Set** (주문 대기열) |
| Realtime | - | - | + **Redis Pub/Sub** (상태 알림) |
| Resilience | try-catch | Circuit Breaker | + Retry + Bulkhead + RateLimiter |
| Lock | DB @Lock | Redisson | + Fencing Token |
| Monitoring | - | - | Prometheus |

---

## 문서

| 문서 | 설명 |
|------|------|
| [아키텍처 진화 개요](docs/00-architecture-overview.md) | 3단계 진화 흐름, 각 단계의 해결/발생 문제 |
| [Monolithic 상세](docs/01-monolithic.md) | @Transactional, Caffeine, DB Lock 패턴 |
| [MSA Basic 상세](docs/02-msa-basic.md) | Saga, OpenFeign, Circuit Breaker, 한계점 |
| [MSA Traffic 상세](docs/03-msa-traffic.md) | 12가지 프로덕션 패턴, 기술, 주의점 |
| [3-Way 코드 비교](docs/04-pattern-comparison.md) | 패턴별 코드 스니펫 비교 |
| [트래픽 시나리오](docs/05-traffic-scenarios.md) | 주문 폭주, 장애, 이중 결제 등 대응 전략 |
| [빌드/실행 가이드](docs/06-setup-guide.md) | Docker 빌드, 인프라 설정 |

---

## Quick Start

```bash
# 1. 인프라 실행 (PostgreSQL, Kafka, Redis)
docker-compose up -d

# 2. Monolithic 빌드
docker run --rm -v "$(pwd)/monolithic:/project" -w /project \
  gradle:8.12-jdk17 gradle build -x test -Djavax.net.ssl.protocols=TLSv1.2

# 3. MSA Traffic 빌드
docker run --rm -v "$(pwd)/msa-traffic:/project" -w /project \
  gradle:8.12-jdk17 gradle build -x test -Djavax.net.ssl.protocols=TLSv1.2
```

자세한 실행 방법은 [빌드/실행 가이드](docs/06-setup-guide.md)를 참고하세요.

---

## 참고

이 레포지토리는 **교육 목적**으로 작성되었습니다.
3가지 아키텍처를 나란히 비교하여 각 패턴의 필요성과 트레이드오프를 학습할 수 있습니다.
