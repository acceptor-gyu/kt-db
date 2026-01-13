# Elasticsearch 설정 가이드

이 프로젝트는 DML, DDL 명령어 로그를 Elasticsearch에 저장하고 분석하여 데이터베이스 optimizer의 EXPLAIN과 유사한 기능을 제공합니다.

## 빠른 시작

### 방법 1: 모든 서비스를 Docker로 실행 (권장)

```bash
# Elasticsearch, Kibana, DB Server를 모두 docker compose로 실행
./scripts/setup-elasticsearch.sh --with-db-server

# 또는 직접
docker compose up -d --build
```

### 방법 2: Elasticsearch/Kibana만 Docker로 실행하고 DB Server는 로컬에서 실행

```bash
# 1. Elasticsearch, Kibana 시작 및 인덱스 초기화
./scripts/setup-elasticsearch.sh

# 2. 예제 실행하여 쿼리 로그 생성
cd db-server
./gradlew runQueryLogExample

# 3. Kibana에서 로그 확인
# 브라우저에서 http://localhost:5601 접속
```

## 사전 요구사항

- Docker & Docker Compose
- Gradle
- Java 17+

## 설치 및 실행

### 1. 서비스 시작

프로젝트 루트 디렉토리에서 docker compose를 사용하여 서비스를 시작합니다:

#### 옵션 A: 모든 서비스 시작 (Elasticsearch + Kibana + DB Server)

```bash
# 권장: 자동 설정 스크립트 사용
./scripts/setup-elasticsearch.sh --with-db-server

# 또는 직접 실행
docker compose up -d --build

# 서비스 상태 확인
docker compose ps
```

#### 옵션 B: Elasticsearch와 Kibana만 시작

```bash
# 자동 설정 스크립트 사용
./scripts/setup-elasticsearch.sh

# 또는 직접 실행
docker compose up -d elasticsearch kibana

# 이후 DB Server는 로컬에서 실행
cd db-server
./gradlew run
```

서비스 상태 확인:

```bash
# 서비스 상태 확인
docker compose ps

# 로그 확인
docker compose logs -f

# Elasticsearch 헬스 체크
curl http://localhost:9200/_cluster/health

# Kibana 상태 확인
curl http://localhost:5601/api/status
```

### 2. Elasticsearch 인덱스 초기화

쿼리 로그를 저장할 인덱스를 생성합니다:

```bash
# 방법 1: 스크립트 사용 (권장)
chmod +x scripts/init-elasticsearch-index.sh
./scripts/init-elasticsearch-index.sh

# 방법 2: Gradle 직접 사용
cd db-server
./gradlew runInitElasticsearch
```

기존 인덱스 삭제 후 재생성:

```bash
./scripts/init-elasticsearch-index.sh --force

# 또는
cd db-server
./gradlew runInitElasticsearch -Pargs="--force"
```

### 3. DB Server 실행

```bash
cd db-server
./gradlew bootRun
```

## 인덱스 스키마

쿼리 로그 인덱스(`db-query-logs`)는 다음 필드를 포함합니다:

| 필드명 | 타입 | 설명 |
|--------|------|------|
| query_id | keyword | 쿼리 고유 ID |
| query_type | keyword | 쿼리 타입 (DDL, DML, DQL, DCL, TCL) |
| query_text | text | 실행된 쿼리 문자열 |
| connection_id | keyword | 연결 ID |
| user | keyword | 사용자명 |
| timestamp | date | 쿼리 실행 시간 |
| execution_time_ms | long | 실행 시간 (밀리초) |
| status | keyword | 쿼리 상태 (SUCCESS, FAILED, IN_PROGRESS) |
| error_message | text | 에러 메시지 (실패시) |
| affected_tables | keyword | 영향받은 테이블 목록 |
| rows_affected | integer | 영향받은 행 수 |
| metadata | object | 추가 메타데이터 |

## 사용 예제

### QueryLogService 사용

```kotlin
import study.db.server.elasticsearch.service.QueryLogService
import study.db.server.elasticsearch.document.QueryLog
import study.db.server.elasticsearch.document.QueryType
import study.db.server.elasticsearch.document.QueryStatus
import java.time.Instant
import java.util.UUID

// 서비스 초기화
val queryLogService = QueryLogService()

// 쿼리 로그 저장
val queryLog = QueryLog(
    queryId = UUID.randomUUID().toString(),
    queryType = QueryType.DDL,
    queryText = "CREATE TABLE users (id INT, name VARCHAR(100))",
    connectionId = "conn-123",
    user = "admin",
    timestamp = Instant.now(),
    executionTimeMs = 150L,
    status = QueryStatus.SUCCESS,
    affectedTables = listOf("users"),
    rowsAffected = 0
)

queryLogService.indexQueryLog(queryLog)

// 쿼리 로그 조회
val log = queryLogService.getQueryLogById(queryLog.queryId)

// 쿼리 로그 검색
val dmlLogs = queryLogService.searchQueryLogs(
    queryType = QueryType.DML,
    tableName = "users",
    limit = 50
)

// 테이블 통계 조회
val stats = queryLogService.getQueryStatistics("users")
println("Total queries: ${stats?.totalQueries}")
println("Average execution time: ${stats?.averageExecutionTimeMs}ms")
println("Success rate: ${stats?.successRate}%")
```

## Kibana 대시보드

Kibana는 docker compose로 자동으로 시작됩니다. 쿼리 로그를 시각화하는 방법:

1. 브라우저에서 http://localhost:5601 접속
2. 왼쪽 메뉴에서 "Management" > "Stack Management" 선택
3. "Kibana" > "Data Views" 선택
4. "Create data view" 클릭
5. 다음과 같이 설정:
   - Name: `db-query-logs`
   - Index pattern: `db-query-logs`
   - Timestamp field: `timestamp`
6. "Save data view to Kibana" 클릭
7. 왼쪽 메뉴에서 "Analytics" > "Discover"로 이동하여 쿼리 로그 탐색

### 유용한 Kibana 쿼리

```
# 특정 테이블에 대한 모든 쿼리
affected_tables: "users"

# 실패한 쿼리
status: "FAILED"

# DDL 쿼리만
query_type: "DDL"

# 실행 시간이 1초 이상인 쿼리
execution_time_ms > 1000
```

## 설정

`db-server/src/main/resources/application.properties`에서 Elasticsearch 연결 설정을 변경할 수 있습니다:

```properties
# Elasticsearch Configuration
elasticsearch.host=localhost
elasticsearch.port=9200
elasticsearch.scheme=http
elasticsearch.index.query-logs=db-query-logs
```

## 문제 해결

### Elasticsearch 연결 실패

```bash
# Elasticsearch 로그 확인
docker compose logs elasticsearch

# 네트워크 연결 확인
curl http://localhost:9200

# 서비스 재시작
docker compose restart elasticsearch
```

### Kibana 접속 안됨

```bash
# Kibana 로그 확인
docker compose logs kibana

# Kibana 재시작
docker compose restart kibana

# Elasticsearch와 함께 재시작
docker compose restart
```

### 인덱스 생성 실패

```bash
# 기존 인덱스 삭제
curl -X DELETE http://localhost:9200/db-query-logs

# 인덱스 재생성
./scripts/init-elasticsearch-index.sh
```

### 메모리 부족

`docker compose.yml`에서 Elasticsearch 메모리 설정 조정:

```yaml
services:
  elasticsearch:
    environment:
      - "ES_JAVA_OPTS=-Xms1g -Xmx1g"  # 512m에서 1g로 증가
```

설정 변경 후 재시작:

```bash
docker compose down
docker compose up -d
```

### 포트 충돌

다른 서비스가 9200 또는 5601 포트를 사용 중인 경우:

```bash
# 포트 사용 중인 프로세스 확인
lsof -i :9200
lsof -i :5601

# docker compose.yml에서 포트 변경
# ports:
#   - "9201:9200"  # 외부 포트 변경
```

## 서비스 관리

### 서비스 시작

```bash
# 모든 서비스 시작 (Elasticsearch, Kibana, DB Server)
docker compose up -d --build

# 특정 서비스만 시작
docker compose up -d elasticsearch kibana

# DB Server만 재시작 (코드 변경 후)
docker compose up -d --build db-server
```

### 서비스 중지

```bash
# 모든 컨테이너 중지 (데이터 보존)
docker compose stop

# 특정 서비스만 중지
docker compose stop db-server

# 컨테이너 중지 및 삭제 (데이터 보존)
docker compose down

# 컨테이너와 볼륨 모두 삭제 (데이터 삭제)
docker compose down -v
```

### 로그 확인

```bash
# 모든 서비스 로그
docker compose logs -f

# 특정 서비스 로그만
docker compose logs -f elasticsearch
docker compose logs -f kibana
docker compose logs -f db-server
```

### 상태 확인

```bash
# 실행중인 서비스 확인
docker compose ps

# 리소스 사용량 확인
docker stats db-elasticsearch db-kibana db-server
```

### DB Server 개발 워크플로우

코드를 변경한 후:

```bash
# DB Server 재빌드 및 재시작
docker compose up -d --build db-server

# 로그 확인하여 정상 시작 확인
docker compose logs -f db-server
```

## 향후 개선 사항

- [ ] 쿼리 실행 계획 분석 기능
- [ ] 인덱스 추천 시스템
- [ ] 실시간 쿼리 모니터링 대시보드

### 후순위
- [ ] 쿼리 성능 최적화 제안
- [ ] 쿼리 패턴 분석 및 캐싱 전략 제안
