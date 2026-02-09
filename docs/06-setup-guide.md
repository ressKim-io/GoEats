# 빌드 및 실행 가이드

---

## 필요한 인프라

| 구분 | Monolithic | MSA Basic | MSA Traffic |
|------|:---------:|:---------:|:-----------:|
| Java 17 | O | O | O |
| PostgreSQL 15 | O | O | O |
| Kafka | - | O | O |
| Redis | - | O | O |
| Zookeeper | - | O | O |

> **DB 스키마 분리**: MSA에서는 서비스별 스키마(`order_schema`, `store_schema`, `payment_schema`, `delivery_schema`)를 사용합니다.
> Monolithic은 기본 `public` 스키마를 사용합니다.

---

## 빌드 방법

### Docker로 빌드 (권장)

로컬에 Gradle을 설치하지 않고 Docker로 빌드합니다.

```bash
# Monolithic
docker run --rm -v "$(pwd)/monolithic:/project" -w /project \
  gradle:8.12-jdk17 gradle build -x test \
  -Djavax.net.ssl.protocols=TLSv1.2

# MSA Basic
docker run --rm -v "$(pwd)/msa:/project" -w /project \
  gradle:8.12-jdk17 gradle build -x test \
  -Djavax.net.ssl.protocols=TLSv1.2

# MSA Traffic
docker run --rm -v "$(pwd)/msa-traffic:/project" -w /project \
  gradle:8.12-jdk17 gradle build -x test \
  -Djavax.net.ssl.protocols=TLSv1.2
```

> **TLS 참고**: 일부 네트워크 환경에서 Maven Central 연결 시 TLS 에러가 발생할 수 있습니다.
> `-Djavax.net.ssl.protocols=TLSv1.2` 플래그로 해결됩니다.

### 로컬 Gradle로 빌드

```bash
# Monolithic
cd monolithic && ./gradlew build

# MSA Basic
cd msa && ./gradlew build

# MSA Traffic
cd msa-traffic && ./gradlew build
```

---

## 인프라 실행

모든 프로젝트는 PostgreSQL이 필요하며, MSA는 추가로 Kafka와 Redis가 필요합니다.
프로젝트 루트의 `docker-compose.yml`로 모든 인프라를 한 번에 실행할 수 있습니다.

```bash
# 프로젝트 루트에서 실행
docker-compose up -d
```

이 명령으로 아래 인프라가 실행됩니다:
- **PostgreSQL 15** (:5432) - DB: `goeats`, 서비스별 스키마 자동 생성
- **Kafka** (:9092) + **Zookeeper** (:2181)
- **Redis 7** (:6379)

> `infra/init.sql`이 PostgreSQL 시작 시 자동 실행되어 4개 스키마를 생성합니다.

---

## 서비스 실행

### Monolithic

```bash
# 1. 인프라 시작 (PostgreSQL)
docker-compose up -d

# 2. 서비스 실행
cd monolithic
./gradlew bootRun
# http://localhost:8080
```

### MSA Basic

```bash
# 1. 인프라 시작 (PostgreSQL + Kafka + Redis)
docker-compose up -d

# 2. 각 서비스 실행 (별도 터미널)
cd msa/store-service    && ../gradlew bootRun  # :8082
cd msa/order-service    && ../gradlew bootRun  # :8081
cd msa/payment-service  && ../gradlew bootRun  # :8083
cd msa/delivery-service && ../gradlew bootRun  # :8084
```

### MSA Traffic

```bash
# 1. 인프라 시작 (PostgreSQL + Kafka + Redis)
docker-compose up -d

# 2. 각 서비스 실행 (별도 터미널)
cd msa-traffic/gateway-service   && ../gradlew bootRun  # :8080 (Gateway)
cd msa-traffic/store-service     && ../gradlew bootRun  # :8082
cd msa-traffic/order-service     && ../gradlew bootRun  # :8081
cd msa-traffic/payment-service   && ../gradlew bootRun  # :8083
cd msa-traffic/delivery-service  && ../gradlew bootRun  # :8084
```

> MSA Traffic은 **Gateway(:8080)**를 통해 접근하는 것이 권장됩니다.

---

## 포트 구성

| 서비스 | Monolithic | MSA Basic | MSA Traffic |
|-------|:---------:|:---------:|:-----------:|
| Gateway | - | - | :8080 |
| Order | :8080 (공유) | :8081 | :8081 |
| Store | :8080 (공유) | :8082 | :8082 |
| Payment | :8080 (공유) | :8083 | :8083 |
| Delivery | :8080 (공유) | :8084 | :8084 |
| PostgreSQL | :5432 | :5432 | :5432 |
| Kafka | - | :9092 | :9092 |
| Redis | - | :6379 | :6379 |
| Zookeeper | - | :2181 | :2181 |

---

## Actuator 엔드포인트 (MSA Traffic)

MSA Traffic 서비스들은 Actuator + Prometheus 메트릭을 노출합니다.

```bash
# Health check
curl http://localhost:8081/actuator/health

# Prometheus metrics
curl http://localhost:8081/actuator/prometheus

# Cache statistics
curl http://localhost:8082/actuator/caches
```

### 유용한 Prometheus 메트릭

```bash
# Circuit Breaker 상태
curl -s http://localhost:8081/actuator/prometheus | grep resilience4j_circuitbreaker_state

# Rate Limiter 가용 허용량
curl -s http://localhost:8081/actuator/prometheus | grep resilience4j_ratelimiter

# Bulkhead 가용 동시 호출
curl -s http://localhost:8081/actuator/prometheus | grep resilience4j_bulkhead
```

---

## 주의사항

1. **PostgreSQL 필수**: `docker-compose up -d`로 PostgreSQL을 실행한 후 서비스를 시작하세요. DDL은 `ddl-auto: update`로 자동 생성됩니다.
2. **스키마 분리**: MSA에서는 서비스별 스키마가 분리되어 있습니다. `infra/init.sql`이 자동으로 스키마를 생성합니다.
3. **Kafka 토픽 자동 생성**: `@RetryableTopic` 사용 시 retry-0, retry-1, retry-2, dlt 토픽이 자동 생성됩니다.
4. **Redis 필수**: MSA Traffic의 Gateway Rate Limiting, Cache, ShedLock, Fencing Token이 모두 Redis에 의존합니다.
5. **서비스 시작 순서**: MSA Traffic에서는 Gateway를 먼저 시작하고, 나머지 서비스를 순서대로 시작하는 것이 좋습니다.
6. **Cache Warming**: store-service 시작 시 DB에서 데이터를 읽어 Redis에 프리로드합니다. DB에 초기 데이터가 없으면 빈 캐시로 시작합니다.
7. **데이터 영속성**: PostgreSQL을 사용하므로 서비스 재시작 후에도 데이터가 유지됩니다. 초기화하려면 `docker-compose down -v`로 볼륨을 삭제하세요.
