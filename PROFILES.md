# Spring Profile 사용 가이드

## Profile 종류

### 1. `local` - 로컬 개발 환경
- Elasticsearch: `localhost:9200`
- db-server: `localhost:9000`
- api-server: `localhost:8080`
- 상세한 DEBUG 로그 출력
- 로컬 파일시스템 사용 (`./data`)

### 2. `prod` - 배포/Docker 환경
- Elasticsearch: `elasticsearch:9200` (컨테이너 내부) / `localhost:9201` (호스트)
- db-server: `db-server:9000` (컨테이너 내부) / `localhost:9001` (호스트)
- api-server: `api-server:8080` (컨테이너 내부) / `localhost:8081` (호스트)
- Kibana: `localhost:5602` (호스트)
- 최소한의 로그 출력 (WARN/INFO)
- Docker 볼륨 사용 (`/app/data`)

### 포트 매핑 요약 (로컬과 Docker 동시 실행 가능)

| 서비스 | 로컬 포트 | Docker 포트 (호스트) | Docker 내부 포트 |
|--------|----------|---------------------|----------------|
| Elasticsearch | 9200 | 9201 | 9200 |
| Kibana | 5601 | 5602 | 5601 |
| db-server | 9000 | 9001 | 9000 |
| api-server | 8080 | 8081 | 8080 |

---

## 로컬 개발 환경 실행

### db-server 실행
```bash
# Gradle로 실행 (기본값이 local profile)
./gradlew :db-server:bootRun

# 또는 명시적으로 local profile 지정
./gradlew :db-server:bootRun --args='--spring.profiles.active=local'

# JAR로 실행
java -jar db-server/build/libs/db-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

### api-server 실행
```bash
# Gradle로 실행 (기본값이 local profile)
./gradlew :api-server:bootRun

# 또는 명시적으로 local profile 지정
./gradlew :api-server:bootRun --args='--spring.profiles.active=local'

# JAR로 실행
java -jar api-server/build/libs/api-server-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

### 로컬 개발 시 필요한 사전 준비
1. Elasticsearch를 로컬에서 실행
   ```bash
   # Docker로 Elasticsearch만 실행
   docker run -d -p 9200:9200 -e "discovery.type=single-node" -e "xpack.security.enabled=false" \
     docker.elastic.co/elasticsearch/elasticsearch:8.11.1
   ```

2. db-server를 먼저 실행

3. api-server 실행

---

## Docker 배포 환경 실행

### 전체 스택 실행 (prod profile 자동 적용)
```bash
# 빌드 및 실행
docker compose up -d

# 로그 확인
docker compose logs -f

# 정지
docker compose down
```

### 개별 서비스 재빌드
```bash
# db-server만 재빌드
docker compose build db-server
docker compose up -d db-server

# api-server만 재빌드
docker compose build api-server
docker compose up -d api-server
```

---

## 설정 파일 구조

```
db-server/src/main/resources/
├── application.properties          # 공통 설정
├── application-local.properties    # 로컬 환경 설정
└── application-prod.properties     # 배포 환경 설정

api-server/src/main/resources/
├── application.properties          # 공통 설정
├── application-local.properties    # 로컬 환경 설정
└── application-prod.properties     # 배포 환경 설정
```

---

## 주요 설정 차이

| 설정 항목 | local | prod (컨테이너 내부) | prod (호스트 접근) |
|---------|-------|---------------------|-------------------|
| Elasticsearch URL | `localhost:9200` | `elasticsearch:9200` | `localhost:9201` |
| db-server | `localhost:9000` | `db-server:9000` | `localhost:9001` |
| api-server | `localhost:8080` | `api-server:8080` | `localhost:8081` |
| 로그 레벨 (study.db) | DEBUG | INFO | INFO |
| 로그 레벨 (root) | INFO | WARN | WARN |
| 저장소 경로 | `./data` | `/app/data` | `/app/data` |

---

## 환경변수 오버라이드

환경변수로 설정을 오버라이드할 수 있습니다:

```bash
# db-server
export SPRING_PROFILES_ACTIVE=local
export SPRING_ELASTICSEARCH_URIS=http://custom-es:9200
export DB_STORAGE_DIRECTORY=/custom/path
./gradlew :db-server:bootRun

# api-server
export SPRING_PROFILES_ACTIVE=local
export DB_SERVER_HOST=custom-host
export DB_SERVER_PORT=9999
./gradlew :api-server:bootRun
```

---

## 로컬과 Docker 동시 실행

포트가 분리되어 있어 **같은 PC에서 로컬 개발 환경과 Docker 환경을 동시에 실행 가능**합니다.

### 1단계: Docker 환경 실행
```bash
docker compose up -d

# 접속 확인
curl http://localhost:9201/_cluster/health  # Elasticsearch
curl http://localhost:8081/api/tables/ping  # API Server
```

### 2단계: 로컬 환경 실행
```bash
# 로컬 Elasticsearch 실행 (필요시)
docker run -d -p 9200:9200 -e "discovery.type=single-node" -e "xpack.security.enabled=false" \
  docker.elastic.co/elasticsearch/elasticsearch:8.11.1

# db-server 로컬 실행
./gradlew :db-server:bootRun

# api-server 로컬 실행 (다른 터미널)
./gradlew :api-server:bootRun

# 접속 확인
curl http://localhost:9200/_cluster/health  # Elasticsearch (로컬)
curl http://localhost:8080/api/tables/ping  # API Server (로컬)
```

### 동시 실행 시 포트 구분

| 환경 | Elasticsearch | db-server | api-server |
|------|--------------|-----------|------------|
| 로컬 | 9200 | 9000 | 8080 |
| Docker | 9201 | 9001 | 8081 |

---

## Troubleshooting

### "Unable to connect to Elasticsearch"
- **로컬 환경**: Elasticsearch가 `localhost:9200`에서 실행 중인지 확인
  ```bash
  curl http://localhost:9200/_cluster/health
  ```
- **Docker 환경**: `localhost:9201`로 접속, elasticsearch 컨테이너 상태 확인
  ```bash
  docker compose ps
  curl http://localhost:9201/_cluster/health
  ```

### "Connection refused to db-server"
- **로컬 환경**: db-server가 `localhost:9000`에서 실행 중인지 확인
  ```bash
  nc -zv localhost 9000
  ```
- **Docker 환경**: `localhost:9001`로 접속, db-server 컨테이너가 healthy 상태인지 확인
  ```bash
  docker compose ps | grep db-server
  nc -zv localhost 9001
  ```

### "API Server not responding"
- **로컬 환경**: `localhost:8080`으로 접속
  ```bash
  curl http://localhost:8080/api/tables/ping
  ```
- **Docker 환경**: `localhost:8081`로 접속
  ```bash
  curl http://localhost:8081/api/tables/ping
  ```

### 포트 충돌 오류
- 로컬과 Docker가 다른 포트를 사용하므로 동시 실행 가능
- 만약 포트 충돌이 발생하면 이미 해당 포트를 사용 중인 프로세스 확인
  ```bash
  # macOS/Linux
  lsof -i :9000
  lsof -i :8080
  ```

### Profile이 적용되지 않음
- `application.properties`에서 `spring.profiles.active` 확인
- 환경변수 `SPRING_PROFILES_ACTIVE` 확인
- 실행 로그에서 "The following profiles are active: local/prod" 메시지 확인
