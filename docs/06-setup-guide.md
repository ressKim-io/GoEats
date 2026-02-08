# 빌드 및 실행 가이드

---

## 필요한 인프라

| 구분 | Monolithic | MSA Basic | MSA Traffic |
|------|:---------:|:---------:|:-----------:|
| Java 17 | O | O | O |
| H2 (In-Memory) | O | O | O |
| Kafka | - | O | O |
| Redis | - | O | O |
| Zookeeper | - | O | O |

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

## 인프라 실행 (MSA 전용)

MSA Basic과 MSA Traffic은 Kafka와 Redis가 필요합니다.

### Docker Compose 예시

```yaml
# docker-compose.yml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
```

```bash
docker-compose up -d
```

---

## 서비스 실행

### Monolithic

```bash
cd monolithic
./gradlew bootRun
# http://localhost:8080
```

### MSA Basic

```bash
# 1. 인프라 시작
docker-compose up -d

# 2. 각 서비스 실행 (별도 터미널)
cd msa/store-service    && ../gradlew bootRun  # :8082
cd msa/order-service    && ../gradlew bootRun  # :8081
cd msa/payment-service  && ../gradlew bootRun  # :8083
cd msa/delivery-service && ../gradlew bootRun  # :8084
```

### MSA Traffic

```bash
# 1. 인프라 시작
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

1. **H2 In-Memory DB**: 서비스 재시작 시 데이터가 초기화됩니다. 교육용 설정입니다.
2. **Kafka 토픽 자동 생성**: `@RetryableTopic` 사용 시 retry-0, retry-1, retry-2, dlt 토픽이 자동 생성됩니다.
3. **Redis 필수**: MSA Traffic의 Gateway Rate Limiting, Cache, ShedLock, Fencing Token이 모두 Redis에 의존합니다.
4. **서비스 시작 순서**: MSA Traffic에서는 Gateway를 먼저 시작하고, 나머지 서비스를 순서대로 시작하는 것이 좋습니다.
5. **Cache Warming**: store-service 시작 시 DB에서 데이터를 읽어 Redis에 프리로드합니다. DB에 초기 데이터가 없으면 빈 캐시로 시작합니다.
