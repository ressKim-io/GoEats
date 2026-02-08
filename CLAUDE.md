# GoEats - Monolithic vs MSA Comparison

## Language
- Response: 한국어
- Code comments: English
- Commit messages: English

## Project Overview
배달 서비스(GoEats) 도메인으로 Monolithic과 MSA의 코드 구조 및 패턴 차이를 비교하는 교육용 레포지토리.

### Directory Structure
```
GoEats/
├── monolithic/          # Single Spring Boot application
│   └── src/main/java/com/goeats/
│       ├── user/        # User domain
│       ├── store/       # Store + Menu domain
│       ├── order/       # Order domain (core flow)
│       ├── payment/     # Payment domain
│       └── delivery/    # Delivery domain
│
├── msa/                 # Multi-service architecture
│   ├── common/          # Shared modules (dto, security, exception)
│   ├── order-service/   # Order microservice
│   ├── payment-service/ # Payment microservice
│   ├── delivery-service/# Delivery microservice
│   └── store-service/   # Store microservice
```

### Domain Terms
| Term | Description |
|------|-------------|
| Order | 주문 (User → Store) |
| OrderItem | 주문 항목 (Menu 기반) |
| Payment | 결제 (PG사 연동) |
| Delivery | 배달 (Rider 매칭 → 픽업 → 배달 완료) |
| Store | 가게 (메뉴 관리) |
| Menu | 메뉴 (가격, 옵션) |
| Rider | 배달 기사 |

## CRITICAL Rules

1. **No Secrets in Code** - Use environment variables
2. **Monolithic vs MSA patterns must contrast clearly**
   - Monolithic: @Transactional, JPA Join, DB Lock, Local Cache
   - MSA: Saga/Event, OpenFeign, Distributed Lock, Redis Cache

## Git Conventions

### Commit Format
```
<type>(<scope>): <subject>
```
Types: feat, fix, docs, style, refactor, test, chore
Scopes: monolithic, msa, common, docs

### Branch Naming
```
feature/phase<N>-<description>
```

## Tech Stack

### Monolithic
- Spring Boot 3, Spring Data JPA, H2/MySQL
- Caffeine Cache, Pessimistic Lock
- ApplicationEventPublisher

### MSA
- Spring Boot 3, Spring Data JPA, Spring Cloud OpenFeign
- Apache Kafka, Redis (Cache + Distributed Lock)
- Resilience4j (Circuit Breaker)
- Redisson (Distributed Lock)

## Skills Reference
- `/spring-data` - JPA patterns
- `/spring-cache` - Caching strategies
- `/spring-testing` - Test patterns
- `/concurrency-spring` - Concurrency control
- `/msa-saga` - Saga pattern
- `/msa-event-driven` - Event-driven architecture
- `/msa-resilience` - Circuit breaker, retry
- `/distributed-lock` - Redis distributed lock
- `/api-design` - REST API design
- `/conventional-commits` - Commit conventions
- `/git-workflow` - Git workflow

## Build & Test
```bash
# Monolithic
cd monolithic && ./gradlew build

# MSA (all services)
cd msa && ./gradlew build
```

---
*Based on ress-claude-agents global + backend-java conventions*
