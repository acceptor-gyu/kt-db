# kt-db

Kotlin으로 구현하는 In-Memory Database

데이터베이스를 처음부터 구현하면서 Kotlin과 네트워크 프로그래밍, 동시성 제어를 학습하는 프로젝트입니다.

## 프로젝트 구조

```
┌─────────────────────────────────────────────────────────────┐
│                        Client Apps                           │
│                    (Multiple Connections)                    │
└────────────────────┬────────────────────────────────────────┘
                     │ TCP/IP
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                       DbTcpServer                            │
│  - 클라이언트 연결 수락                                        │
│  - ConnectionManager, TableService 싱글톤 관리                │
└───────────┬─────────────────────────────────────────────────┘
            │
            ├──────────────────┬──────────────────┐
            ▼                  ▼                  ▼
    ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
    │ Connection #1│   │ Connection #2│   │ Connection #N│
    │  Handler     │   │  Handler     │   │  Handler     │
    │ (Thread A)   │   │ (Thread B)   │   │ (Thread N)   │
    └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
           │                  │                  │
           └──────────────────┼──────────────────┘
                              │ 공유 접근 (Thread-safe)
                              ▼
           ┌──────────────────────────────────────┐
           │       ConnectionManager              │
           │  - Connection ID 생성 (AtomicLong)   │
           │  - 활성 연결 추적 (ConcurrentHashMap)│
           │  - register() / unregister()         │
           └──────────────────────────────────────┘
                              │
                              ▼
           ┌──────────────────────────────────────┐
           │         TableService                 │
           │  - 테이블 생성/조회/삭제/수정          │
           │  - ConcurrentHashMap 기반            │
           │  - Atomic Operations (compute 등)   │
           │  - 모든 연결이 공유하는 단일 인스턴스   │
           └──────────────────────────────────────┘
```

### 주요 컴포넌트

#### 1. DbTcpServer
- **역할**: TCP 연결 수락 및 ConnectionHandler 생성
- **인스턴스**: 1개 (서버 시작 시)
- **책임**:
  - ServerSocket으로 클라이언트 연결 수락
  - ConnectionManager와 TableService 싱글톤 관리
  - 각 연결마다 새 ConnectionHandler 생성 및 스레드 풀에 제출

#### 2. ConnectionManager
- **역할**: 연결 생명주기 관리
- **인스턴스**: 1개 (DbTcpServer가 소유)
- **책임**:
  - Connection ID 생성 (AtomicLong 기반)
  - 활성 연결 추적 (ConcurrentHashMap)
  - 연결 등록/해제
  - SHOW PROCESSLIST, KILL 명령 지원 (향후)

#### 3. ConnectionHandler
- **역할**: 개별 클라이언트 연결 처리
- **인스턴스**: 클라이언트 연결당 1개
- **책임**:
  - 핸드셰이크 및 인증
  - 클라이언트 명령 파싱 및 처리
  - TableService 호출
  - 연결 종료 시 리소스 정리 및 ConnectionManager에서 unregister

#### 4. TableService
- **역할**: In-memory 데이터 저장소
- **인스턴스**: 1개 (모든 연결이 공유)
- **Thread-Safety**:
  - `ConcurrentHashMap` 사용
  - `compute()`, `putIfAbsent()` 등 atomic operation
  - 여러 ConnectionHandler의 동시 접근 안전

## 요청 처리 흐름

```
┌─────────┐                                              ┌──────────────┐
│ Client  │                                              │ DbTcpServer  │
└────┬────┘                                              └──────┬───────┘
     │                                                           │
     │ 1. TCP Connection Request                                │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                    2. Generate Connection ID
     │                                       (ConnectionManager.generateConnectionId())
     │                                                           │
     │                                    3. Create ConnectionHandler
     │                                       - connectionId: 1   │
     │                                       - socket            │
     │                                       - tableService      │
     │                                       - connectionManager │
     │                                                           │
     │                                    4. Register Connection
     │                                       (ConnectionManager.register())
     │                                                           │
     │                                    5. Submit to Thread Pool
     │                                       (executor.submit())
     │                                                           │
     │                                                      ┌────▼────────┐
     │                                                      │Connection   │
     │                                                      │Handler #1   │
     │                                                      │(Thread A)   │
     │                                                      └────┬────────┘
     │ 6. Send Handshake (HANDSHAKE|v1.0)                       │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 7. Auth Request (AUTH|user|password)                     │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      8. Verify Credentials
     │                                                           │
     │ 9. Auth Response (Authenticated)                         │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 10. Command Request                                      │
     │     (CREATE TABLE users ...)                             │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      11. Parse Command   │
     │                                                           │
     │                                      12. Call TableService
     │                                          (thread-safe)   │
     │                                          ┌────▼────────┐ │
     │                                          │TableService │ │
     │                                          │(Shared)     │ │
     │                                          │ConcurrentMap│ │
     │                                          └────┬────────┘ │
     │                                               │          │
     │                                      13. Execute Operation
     │                                          (CREATE/INSERT/SELECT)
     │                                               │          │
     │                                          ┌────▼────────┐ │
     │                                          │  Result     │ │
     │                                          └────┬────────┘ │
     │                                               │          │
     │ 14. Response                              ◀──┘          │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 15. Close Connection                                     │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      16. Close Resources │
     │                                          - input.close() │
     │                                          - output.close()│
     │                                          - socket.close()│
     │                                                           │
     │                                      17. Unregister      │
     │                                          (ConnectionManager.unregister())
     │                                                           │
     └───────────────────────────────────────────────────────────┘
```

## Thread-Safety 보장

### ConnectionManager
- `AtomicLong`을 사용한 Connection ID 생성 (동시 호출 시 고유 ID 보장)
- `ConcurrentHashMap`으로 활성 연결 추적 (동시 읽기/쓰기 안전)

### TableService
- `ConcurrentHashMap` 사용
- **Atomic Operations**:
  - `createTable()`: `putIfAbsent()` - 중복 생성 방지
  - `insert()`: `compute()` - read-modify-write를 atomic하게 처리
  - `dropTable()`: `remove()` - atomic 삭제

#### insert() Race Condition 방지 예시

```kotlin
// ❌ Thread-unsafe (Race Condition 발생!)
val table = tables[tableName]           // Thread A, B 동시 읽기
val updated = table.copy(value = ...)   // Thread A, B 각자 수정
tables[tableName] = updated             // 하나의 수정이 손실됨!

// ✅ Thread-safe (Atomic Operation)
tables.compute(tableName) { _, existingTable ->
    existingTable?.copy(value = existingTable.value + values)
}
// compute() 내부는 하나의 atomic operation
// Thread B는 Thread A가 끝날 때까지 대기
```

## Connection ID 관리

```kotlin
// 초기 상태
connectionIdGenerator = AtomicLong(0)

// 연결 1~10 생성
generateConnectionId() → 1, 2, 3, ..., 10

// 연결 3, 7 종료
unregister(3)  // ID generator는 변하지 않음 (여전히 10)
unregister(7)  // connections Map에서만 제거

// 새 연결 5개 생성
generateConnectionId() → 11, 12, 13, 14, 15  // 3과 7을 재사용하지 않음!

// 활성 연결: {1, 2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15}
// ID는 절대 재사용되지 않음 (로그 추적에 유리)
```

## EXPLAIN 요청 처리 흐름

`EXPLAIN` 명령은 SQL 쿼리의 실행 계획을 생성하여 옵티마이저가 어떻게 쿼리를 실행할지 보여줍니다. MySQL의 `EXPLAIN` 명령과 유사합니다.

### EXPLAIN 아키텍처

```
┌────────────────────────────────────────────────────────────────────────┐
│                          EXPLAIN Command                               │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │
                 ┌───────────────▼────────────────┐
                 │   ConnectionHandler            │
                 │   handleExplain(request)       │
                 └───────────────┬────────────────┘
                                 │
                 ┌───────────────▼────────────────┐
                 │      ExplainService            │
                 │   explain(sql): QueryPlan      │
                 └───┬─────┬──────┬──────┬────────┘
                     │     │      │      │
        ┌────────────┼─────┼──────┼──────┼────────────┐
        │            │     │      │      │            │
        ▼            ▼     ▼      ▼      ▼            ▼
┌──────────────┐ ┌──────┐ ┌────┐ ┌────┐ ┌──────────────┐
│  SqlParser   │ │Table │ │Index│ │Table│ │  QueryPlan   │
│              │ │Meta  │ │Meta │ │Stats│ │  Repository  │
│ parseQuery() │ │Service│ │Service│ │Service│ │              │
│              │ │      │ │    │ │    │ │ save(plan)   │
│ ParsedQuery  │ │exists│ │should│ │calc│ │              │
│  - tableName │ │columns│ │Use   │ │Sel.│ │              │
│  - where[]   │ │      │ │Index │ │    │ │              │
│  - select[]  │ │      │ │      │ │    │ │              │
└──────────────┘ └──────┘ └────┘ └────┘ └──────────────┘
        │                                       │
        │                                       │
        └───────────────┬───────────────────────┘
                        │
        ┌───────────────▼────────────────┐
        │      ExecutionStep 생성         │
        │                                 │
        │  - TABLE_SCAN                   │
        │  - INDEX_SCAN                   │
        │  - COVERED_INDEX_SCAN           │
        │                                 │
        │  비용 계산:                       │
        │  - INDEX_SCAN: log2(N)*sel*N    │
        │  - TABLE_SCAN: N                │
        │  - Selectivity: distinct/total  │
        │                                 │
        │  Covered Query 판단:             │
        │  1. WHERE 컬럼 = leading column │
        │  2. SELECT 컬럼 ⊆ 인덱스 컬럼     │
        └─────────────────────────────────┘
```

### EXPLAIN 처리 단계 (상세)

```
┌─────────┐                                              ┌──────────────────┐
│ Client  │                                              │  DB Server       │
└────┬────┘                                              └────────┬─────────┘
     │                                                            │
     │ 1. EXPLAIN Query (sql: "SELECT * FROM users WHERE...")    │
     │────────────────────────────────────────────────────────────▶│
     │                                                            │
     │                                    ┌───────────────────────▼──────┐
     │                                    │   ConnectionHandler           │
     │                                    │   handleExplain(request)      │
     │                                    └───────────────────────┬──────┘
     │                                                            │
     │                                    2. Extract SQL from request
     │                                       sql = request.sql    │
     │                                                            │
     │                                    ┌───────────────────────▼──────┐
     │                                    │      ExplainService           │
     │                                    │      explain(sql)             │
     │                                    └───────────────────────┬──────┘
     │                                                            │
     │                                    3. SQL 파싱 (SqlParser)
     │                                       ParsedQuery:         │
     │                                       - tableName: "users" │
     │                                       - where: [name="Alice"]
     │                                       - select: ["*"]      │
     │                                                            │
     │                                    4. 테이블 메타데이터 조회
     │                                       tableMetadataService.tableExists()
     │                                       tableMetadataService.getColumns()
     │                                                            │
     │                                    5. 인덱스 결정 (IndexMetadataService)
     │                                       shouldUseIndexScan(table, column, selectivity)
     │                                       → IndexScanDecision:  │
     │                                         - useIndex: true/false
     │                                         - selectedIndex: "idx_name"
     │                                         - reason: "..."    │
     │                                                            │
     │                                    6. 선택도(Selectivity) 계산
     │                                       tableStatisticsService.calculateSelectivity()
     │                                       selectivity = distinctCount / totalRows
     │                                                            │
     │                                    7. ExecutionStep 생성
     │                                       if (useIndex && isCovered)
     │                                         → COVERED_INDEX_SCAN
     │                                       else if (useIndex)    │
     │                                         → INDEX_SCAN       │
     │                                       else                 │
     │                                         → TABLE_SCAN       │
     │                                                            │
     │                                    8. 비용 계산             │
     │                                       INDEX_SCAN:          │
     │                                         cost = log2(totalRows) + totalRows * selectivity
     │                                       TABLE_SCAN:          │
     │                                         cost = totalRows   │
     │                                                            │
     │                                    9. Covered Query 판단
     │                                       조건 1: whereColumn == indexColumns[0]
     │                                       조건 2: selectColumns ⊆ indexColumns
     │                                                            │
     │                                    10. QueryPlan 생성
     │                                        QueryPlan:          │
     │                                        - planId: UUID      │
     │                                        - queryHash: MD5(sql)
     │                                        - executionSteps: [] │
     │                                        - estimatedCost: 123.45
     │                                        - estimatedRows: 500 │
     │                                        - isCoveredQuery: true/false
     │                                                            │
     │                                    11. Elasticsearch 저장
     │                                        queryPlanRepository.save(queryPlan)
     │                                        → Index: db-query-plans
     │                                                            │
     │ 12. QueryPlan JSON 응답                                    │
     │◀────────────────────────────────────────────────────────────│
     │    {                                                       │
     │      "planId": "uuid-...",                                 │
     │      "executionSteps": [                                   │
     │        {                                                   │
     │          "stepType": "COVERED_INDEX_SCAN",                 │
     │          "tableName": "users",                             │
     │          "indexUsed": "idx_name_email",                    │
     │          "filterCondition": "name = 'Alice'",              │
     │          "columnsAccessed": ["name", "email"],             │
     │          "estimatedCost": 15.3,                            │
     │          "estimatedRows": 10,                              │
     │          "isCovered": true,                                │
     │          "description": "Using covering index..."          │
     │        }                                                   │
     │      ],                                                    │
     │      "estimatedCost": 15.3,                                │
     │      "estimatedRows": 10,                                  │
     │      "isCoveredQuery": true                                │
     │    }                                                       │
     └───────────────────────────────────────────────────────────────
```

### Covered Query 판단 로직

**Covered Query**는 인덱스만으로 쿼리를 완전히 처리할 수 있어 테이블 데이터를 읽지 않아도 되는 최적화된 쿼리입니다.

```kotlin
// 예시: INDEX (name, email, age)

// ✅ COVERED QUERY
SELECT name, email FROM users WHERE name = 'Alice'
→ WHERE 컬럼(name)이 leading column ✓
→ SELECT 컬럼(name, email)이 모두 인덱스에 포함 ✓
→ Result: COVERED_INDEX_SCAN (매우 빠름!)

// ❌ NOT COVERED
SELECT name, email, address FROM users WHERE name = 'Alice'
→ WHERE 컬럼(name)이 leading column ✓
→ SELECT 컬럼에 address가 있는데 인덱스에 없음 ✗
→ Result: INDEX_SCAN (인덱스 사용하지만 테이블 접근 필요)

// ❌ NOT COVERED
SELECT name, email FROM users WHERE email = 'alice@example.com'
→ WHERE 컬럼(email)이 leading column 아님 ✗
→ Result: TABLE_SCAN (인덱스 사용 불가)
```

### 비용 계산 공식

```kotlin
// INDEX_SCAN 비용
cost = log2(totalRows) + (totalRows * selectivity)
     = 인덱스 탐색 비용 + 실제 데이터 읽기 비용

// 예시: 총 1,000,000 행, selectivity = 0.001 (0.1%)
cost = log2(1,000,000) + (1,000,000 * 0.001)
     = 19.93 + 1,000
     = 1,019.93

// TABLE_SCAN 비용
cost = totalRows
     = 1,000,000  (모든 행 스캔)

// → INDEX_SCAN이 약 1000배 빠름!
```

### QueryPlan vs QueryLog 차이

| 항목 | QueryPlan | QueryLog |
|-----|-----------|----------|
| **용도** | EXPLAIN 실행 계획 | 실제 쿼리 실행 기록 |
| **생성 시점** | EXPLAIN 명령 시 | 모든 쿼리 실행 시 |
| **인덱스** | `db-query-plans` | `db-query-logs` |
| **주요 정보** | 실행 계획, 비용, Covered 여부 | 실행 시간, 상태, 에러 |
| **목적** | 쿼리 최적화 분석 | 성능 모니터링, 로깅 |

## Query Logging & Monitoring Architecture

쿼리 실행 로그를 Elasticsearch에 저장하고 Kibana로 시각화하여 데이터베이스 옵티마이저의 EXPLAIN과 유사한 기능을 제공합니다.

### 전체 아키텍처

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Docker Compose                                  │
│                                                                          │
│  ┌─────────────────────┐  ┌──────────────────┐  ┌───────────────────┐    │
│  │   Elasticsearch     │  │     Kibana       │  │    DB Server      │    │
│  │   (Port 9200)       │  │   (Port 5601)    │  │   (Port 9000)     │    │
│  │                     │  │                  │  │                   │    │
│  │ - 쿼리 로그 인덱싱      │◀─│ - 로그 시각화       │  │ - TCP Server      │    │
│  │ - 검색 및 집계         │  │ - 대시보드         │   │ - Query Execution │    │
│  │ - db-query-logs     │  │ - 통계 분석        │   │ - Connection Mgmt │    │
│  └─────────▲───────────┘  └──────────────────┘   └─────────┬─────────┘   │
│            │                                               │             │
└────────────┼───────────────────────────────────────────────┼─────────────┘
             │                                               │
             │ Index query logs                              │
             │ (HTTP REST API)                               │
             │                                               │
             └───────────────────────────────────────────────┘
                         Query execution flow
```

### 쿼리 로깅 데이터 흐름

```
┌─────────┐                                              ┌──────────────────┐
│ Client  │                                              │  DB Server       │
└────┬────┘                                              │  (db-server)     │
     │                                                   └────────┬─────────┘
     │ 1. SQL Query (CREATE TABLE, INSERT, SELECT...)            │
     │───────────────────────────────────────────────────────────▶│
     │                                                            │
     │                                         2. ConnectionHandler
     │                                            processes query │
     │                                                            │
     │                                         ┌──────────────────▼────────┐
     │                                         │    TableService           │
     │                                         │  - Execute query          │
     │                                         │  - Create/Insert/Select   │
     │                                         └──────────────┬────────────┘
     │                                                        │
     │                                         3. Query executed
     │                                                        │
     │                                         ┌──────────────▼────────────┐
     │                                         │   QueryLogService         │
     │                                         │  - Create QueryLog        │
     │                                         │  - queryType: DDL/DML     │
     │                                         │  - queryText, timestamp   │
     │                                         │  - executionTimeMs        │
     │                                         │  - status: SUCCESS/FAILED │
     │                                         │  - affectedTables         │
     │                                         └──────────────┬────────────┘
     │                                                        │
     │                                         4. Index query log
     │                                            (Spring Data Elasticsearch)
     │                                                        │
     │                                         ┌──────────────▼────────────┐
     │                                         │   QueryLogRepository      │
     │                                         │  - save(queryLog)         │
     │                                         │  - ElasticsearchRepository│
     │                                         └──────────────┬────────────┘
     │                                                        │
     │                                                        │ HTTP POST
     │                                                        │ /_doc
     │                                         ┌──────────────▼────────────┐
     │                                         │    Elasticsearch          │
     │                                         │  Index: db-query-logs     │
     │                                         │                           │
     │                                         │  Documents:               │
     │                                         │  {                        │
     │                                         │    query_id: "uuid",      │
     │                                         │    query_type: "DDL",     │
     │                                         │    query_text: "CREATE.." │
     │                                         │    timestamp: "2024-...", │
     │                                         │    execution_time_ms: 45, │
     │                                         │    status: "SUCCESS",     │
     │                                         │    affected_tables: [...] │
     │                                         │  }                        │
     │                                         └──────────────┬────────────┘
     │                                                        │
     │ 5. Query result                                        │
     │◀───────────────────────────────────────────────────────┤
     │                                                        │
     │                                                        │
     │                                         ┌──────────────▼────────────┐
     │                                         │       Kibana              │
     │                                         │                           │
     │                                         │  - Query logs discovery   │
     │                                         │  - Visualization          │
     │                                         │  - Statistics dashboard   │
     │                                         │                           │
     │                                         │  Queries:                 │
     │                                         │  - affected_tables:"users"│
     │                                         │  - query_type:"DDL"       │
     │                                         │  - execution_time_ms>1000 │
     │                                         └───────────────────────────┘
     │
     └───────────────────────────────────────────────────────────────────────
```

### 쿼리 로그 데이터 모델

Elasticsearch에 저장되는 쿼리 로그 문서 구조:

```kotlin
@Document(indexName = "db-query-logs")
data class QueryLog(
    @Id val queryId: String,                    // UUID
    val queryType: QueryType,                   // DDL, DML, DQL, DCL, TCL
    val queryText: String,                      // 실행된 SQL 쿼리
    val connectionId: String,                   // 연결 ID
    val user: String,                           // 사용자명
    val timestamp: Instant,                     // 실행 시간
    val executionTimeMs: Long?,                 // 실행 시간 (밀리초)
    val status: QueryStatus,                    // SUCCESS, FAILED, IN_PROGRESS
    val errorMessage: String? = null,           // 에러 메시지
    val affectedTables: List<String>,           // 영향받은 테이블 목록
    val rowsAffected: Int? = null,              // 영향받은 행 수
    val metadata: Map<String, Any>? = null      // 추가 메타데이터
)
```

### Docker Compose 서비스 구성

```yaml
services:
  elasticsearch:
    - DML/DDL 쿼리 로그 저장
    - 검색 및 집계 기능
    - Port: 9200 (HTTP), 9300 (Transport)

  kibana:
    - Elasticsearch 데이터 시각화
    - 대시보드 및 통계
    - Port: 5601 (Web UI)

  db-server:
    - TCP 서버 (Port 9000)
    - 쿼리 실행 및 로깅
    - Elasticsearch 연동
```

### 빠른 시작

```bash
# 1. 모든 서비스 시작 (Elasticsearch + Kibana + DB Server)
docker compose up -d --build

# 2. 서비스 상태 확인
docker compose ps

# 3. Elasticsearch 인덱스 초기화
./scripts/init-elasticsearch-index.sh

# 4. 예제 실행하여 쿼리 로그 생성
cd db-server
./gradlew runQueryLogExample

# 5. Kibana에서 로그 확인
# 브라우저에서 http://localhost:5601 접속
```

자세한 Elasticsearch 설정 및 사용법은 [ELASTICSEARCH.md](./ELASTICSEARCH.md)를 참조하세요.

## 개발 환경

- **언어**: Kotlin 1.9.25
- **프레임워크**: Spring Boot 3.5.3
- **JDK**: Java 17
- **빌드 도구**: Gradle
- **모니터링**: Elasticsearch 8.11.1, Kibana 8.11.1
- **컨테이너**: Docker & Docker Compose

## 빌드 및 실행

```bash
# 빌드
./gradlew build

# 서버 실행
./gradlew bootRun

# 테스트
./gradlew test
```
