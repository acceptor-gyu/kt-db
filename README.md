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
  - SQL 문자열 수신 및 파싱
  - 클라이언트 명령 처리
  - TableService 호출
  - 연결 종료 시 리소스 정리 및 ConnectionManager에서 unregister

#### 4. TableService
- **역할**: In-memory 데이터 저장소
- **인스턴스**: 1개 (모든 연결이 공유)
- **Thread-Safety**:
  - `ConcurrentHashMap` 사용
  - `compute()`, `putIfAbsent()` 등 atomic operation
  - 여러 ConnectionHandler의 동시 접근 안전

### 디스크 기반 영속성 (Disk I/O)

데이터베이스는 파일 기반 영속성을 지원하여 서버 재시작 후에도 데이터가 보존됩니다.

#### 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                     TableService                             │
│  ┌───────────────────────────────────────────────┐          │
│  │      In-Memory Cache (ConcurrentHashMap)      │          │
│  │         - 빠른 쓰기 성능                        │          │
│  │         - Thread-safe 동시 접근                │          │
│  └─────────────────┬─────────────────────────────┘          │
│                    │                                         │
│                    ▼                                         │
│  ┌───────────────────────────────────────────────┐          │
│  │          TableFileManager                      │          │
│  │   - CREATE TABLE: 파일 생성 (*.dat)           │          │
│  │   - INSERT: 파일 업데이트                      │          │
│  │   - SELECT: 파일에서 직접 읽기 (full scan)     │          │
│  │   - DROP TABLE: 파일 삭제                      │          │
│  └─────────────────┬─────────────────────────────┘          │
└────────────────────┼─────────────────────────────────────────┘
                     │
                     ▼
           ┌─────────────────────┐
           │    Disk Storage     │
           │  ./data/            │
           │  ├─ users.dat       │
           │  ├─ products.dat    │
           │  └─ orders.dat      │
           └─────────────────────┘
```

#### 바이너리 파일 포맷

각 테이블은 `.dat` 파일로 저장되며, 다음과 같은 구조를 가집니다:

```
┌─────────────────────────────────────┐
│ File Header (24 bytes)              │
│  - Magic: 0xDBF0 (2 bytes)          │
│  - Version: 1 (2 bytes)             │
│  - Row Count: long (8 bytes)        │
│  - Column Count: int (4 bytes)      │
│  - Schema Length: int (4 bytes)     │
│  - Reserved: (4 bytes)              │
├─────────────────────────────────────┤
│ Schema Section (variable)           │
│  For each column:                   │
│   - Name Length: short (2 bytes)    │
│   - Name: UTF-8 bytes               │
│   - Type: byte (1 byte)             │
│     0x01=INT, 0x02=VARCHAR,         │
│     0x03=TIMESTAMP, 0x04=BOOLEAN    │
├─────────────────────────────────────┤
│ Data Section (variable)             │
│  For each row:                      │
│   - Row Length: int (4 bytes)       │
│   - Field Data (type-specific):     │
│     INT: 4 bytes (Big Endian)       │
│     VARCHAR: [2-byte len][UTF-8]    │
│     TIMESTAMP: 8 bytes (Unix ms)    │
│     BOOLEAN: 1 byte (0x00/0x01)     │
└─────────────────────────────────────┘
```

#### 영속성 동작

**CREATE TABLE**:
```kotlin
tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
// → 파일 생성: ./data/users.dat
```

**INSERT**:
```kotlin
tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
// → 파일 업데이트: users.dat에 row 추가
```

**SELECT (Full Table Scan)**:
```kotlin
val table = tableService.select("users")
// → 파일에서 직접 읽기: users.dat를 디코딩하여 Table 객체 반환
```

**서버 재시작**:
```kotlin
// 1. 서버 시작 시 TableService 초기화
// 2. TableFileManager가 ./data/ 디렉토리의 모든 .dat 파일 스캔
// 3. 각 파일을 읽어서 Table 객체로 복원
// 4. In-memory cache에 로드
```

**DROP TABLE**:
```kotlin
tableService.dropTable("users")
// → 파일 삭제: ./data/users.dat 제거
```

#### 데이터 무결성 보장

- **Atomic Writes**: Temp file → sync → rename 패턴으로 원자적 쓰기 보장
- **Crash Recovery**: 파일이 완전히 쓰여지지 않으면 temp 파일만 남고 원본은 보존됨
- **Thread-Safe**: 파일 쓰기는 TableService의 ConcurrentHashMap atomic operation과 통합

#### 설정

`application.properties`:
```properties
db.storage.directory=./data
db.storage.enabled=true
```

## 요청 처리 흐름

### 프로토콜 개요

**단순화된 String 기반 프로토콜:**
- 클라이언트 → 서버: SQL 문자열 (UTF-8 인코딩)
- 서버 → 클라이언트: JSON 형식의 DbResponse

```
Client                          Server
  │                               │
  │  [Length: 4 bytes]            │
  │  [SQL String: "SELECT ..."]   │
  │──────────────────────────────▶│
  │                               │
  │  [Length: 4 bytes]            │
  │  [JSON Response: {...}]       │
  │◀──────────────────────────────│
```

### 상세 처리 흐름

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
     │ 6. SQL Request (String)                                  │
     │    ProtocolCodec.encodeRequest("CREATE TABLE users ...") │
     │    → [4-byte length][UTF-8 SQL string]                   │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      7. Decode SQL String
     │                                         ProtocolCodec.decodeRequest()
     │                                         → "CREATE TABLE users ..."
     │                                                           │
     │                                      8. Parse SQL        │
     │                                         - CREATE TABLE   │
     │                                         - INSERT INTO    │
     │                                         - SELECT         │
     │                                         - EXPLAIN        │
     │                                         - PING           │
     │                                                           │
     │                                      9. Call TableService
     │                                          (thread-safe)   │
     │                                          ┌────▼────────┐ │
     │                                          │TableService │ │
     │                                          │(Shared)     │ │
     │                                          │ConcurrentMap│ │
     │                                          └────┬────────┘ │
     │                                               │          │
     │                                      10. Execute Operation
     │                                          (CREATE/INSERT/SELECT)
     │                                               │          │
     │                                          ┌────▼────────┐ │
     │                                          │  Result     │ │
     │                                          └────┬────────┘ │
     │                                               │          │
     │                                      11. Create DbResponse
     │                                          { success: true,
     │                                            message: "...",
     │                                            data: "..." }
     │                                                           │
     │ 12. JSON Response                                        │
     │     ProtocolCodec.encodeResponse(DbResponse)             │
     │     → [4-byte length][UTF-8 JSON]                        │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 13. Close Connection                                     │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      14. Close Resources │
     │                                          - input.close() │
     │                                          - output.close()│
     │                                          - socket.close()│
     │                                                           │
     │                                      15. Unregister      │
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

### 방법 1: Docker Compose (추천)

전체 시스템을 한 번에 실행합니다 (Elasticsearch + Kibana + DB Server + API Server).

```bash
# 전체 서비스 실행
docker compose up -d --build

# 서비스 상태 확인
docker compose ps

# API 테스트
curl http://localhost:8080/api/tables/ping

# 로그 확인
docker compose logs -f api-server

# 서비스 중지
docker compose down
```

**포트 정보 (Docker):**
- API Server: `http://localhost:8081`
- DB Server: `tcp://localhost:9001`
- Elasticsearch: `http://localhost:9201`
- Kibana: `http://localhost:5602`

**포트 정보 (로컬):**
- API Server: `http://localhost:8080`
- DB Server: `tcp://localhost:9000`
- Elasticsearch: `http://localhost:9200`
- Kibana: `http://localhost:5601`

**동시 실행 가능:** 로컬과 Docker가 서로 다른 포트를 사용하므로 같은 PC에서 동시에 실행할 수 있습니다. 자세한 내용은 [PROFILES.md](./PROFILES.md)를 참조하세요.

**데이터 영속성:**
- 테이블 데이터: `db_db-server-data` 볼륨에 저장
- Elasticsearch 데이터: `db_elasticsearch-data` 볼륨에 저장

자세한 Docker 사용법은 [DOCKER_GUIDE.md](./DOCKER_GUIDE.md)를 참조하세요.

### 방법 2: 로컬 실행 (Gradle)

Gradle을 사용하여 직접 실행합니다.

```bash
# 전체 빌드
./gradlew build

# DB 서버 실행
./gradlew :db-server:bootRun

# API 서버 실행 (별도 터미널)
./gradlew :api-server:bootRun

# 테스트
./gradlew test
```

**주의:** Elasticsearch와 Kibana가 필요한 EXPLAIN 기능을 사용하려면 먼저 Docker Compose로 Elasticsearch를 실행해야 합니다:
```bash
docker compose up -d elasticsearch kibana
```

## API 사용 방법

### REST API 엔드포인트

API 서버는 HTTP REST API를 제공합니다.

**Docker 환경 (포트 8081):**

```bash
# CREATE TABLE
curl -X POST http://localhost:8081/api/tables/create \
  -H "Content-Type: application/json" \
  -d '{"query": "CREATE TABLE users (id INT, name VARCHAR, age INT)"}'

# INSERT
curl -X POST http://localhost:8081/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{"query": "INSERT INTO users VALUES (id=\"1\", name=\"John\", age=\"30\")"}'

# SELECT
curl -X GET 'http://localhost:8081/api/tables/select?query=SELECT%20*%20FROM%20users'

# EXPLAIN
curl -X GET 'http://localhost:8081/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20users'

# DROP TABLE
curl -X DELETE 'http://localhost:8081/api/tables/drop?query=DROP%20TABLE%20users'
```

**로컬 환경 (포트 8080):**

```bash
# 포트만 8080으로 변경하면 됩니다
curl -X POST http://localhost:8080/api/tables/create ...
```

### 자동화된 테스트

```bash
# 전체 테스트 시나리오 실행 (5개 테이블, 19개 레코드)
cd api-server
./test-api-requests.sh
```

자세한 API 사용법은 다음 문서를 참조하세요:
- [API_USAGE.md](./api-server/API_USAGE.md) - 전체 API 가이드
- [TEST_README.md](./api-server/TEST_README.md) - 테스트 방법

## 프로토콜 아키텍처

### 단순화된 String 기반 프로토콜

프로젝트 초기에는 `DbRequest`와 `DbCommand` enum을 사용한 구조화된 프로토콜을 사용했으나, 불필요한 복잡성을 제거하고 **순수 String 기반 프로토콜**로 단순화했습니다.

#### 이전 프로토콜 (복잡)

```kotlin
// 클라이언트 측
val request = DbRequest(
    command = DbCommand.RAW_SQL,
    sql = "CREATE TABLE users (id INT, name VARCHAR)"
)
val requestBytes = ProtocolCodec.encodeRequest(request)  // JSON 직렬화
// → {"command":"RAW_SQL","sql":"CREATE TABLE..."}

// 서버 측
val request = ProtocolCodec.decodeRequest(bytes)  // JSON 역직렬화
val sql = request.sql  // SQL 추출
// SQL 파싱 및 처리...
```

**문제점:**
- 중복된 계층: `DbCommand.RAW_SQL`이 단순히 SQL 문자열을 감싸는 래퍼 역할만 수행
- 불필요한 JSON 직렬화/역직렬화 오버헤드
- DbRequest, DbCommand 클래스 유지보수 필요

#### 현재 프로토콜 (단순)

```kotlin
// 클라이언트 측
val sql = "CREATE TABLE users (id INT, name VARCHAR)"
val requestBytes = ProtocolCodec.encodeRequest(sql)  // UTF-8 인코딩
// → "CREATE TABLE users..."

// 서버 측
val sql = ProtocolCodec.decodeRequest(bytes)  // UTF-8 디코딩
// SQL 파싱 및 처리...
```

**장점:**
- 단순성: SQL 문자열을 직접 전송
- 성능: JSON 직렬화 불필요, UTF-8 인코딩만 수행
- 유지보수: 중간 DTO 클래스 제거

### 프로토콜 메시지 형식

#### 요청 메시지 (Client → Server)

```
┌────────────────┬──────────────────────────────┐
│ Length (4 byte)│ SQL String (UTF-8)           │
├────────────────┼──────────────────────────────┤
│ 0x00000023     │ "CREATE TABLE users ..."     │
└────────────────┴──────────────────────────────┘
     35 bytes           SQL 내용
```

#### 응답 메시지 (Server → Client)

```
┌────────────────┬──────────────────────────────┐
│ Length (4 byte)│ JSON Response (UTF-8)        │
├────────────────┼──────────────────────────────┤
│ 0x0000004A     │ {"success":true,...}         │
└────────────────┴──────────────────────────────┘
     74 bytes          DbResponse JSON
```

### ProtocolCodec 구현

```kotlin
object ProtocolCodec {
    // 요청 인코딩: SQL 문자열 → 바이트 배열
    fun encodeRequest(sql: String): ByteArray {
        return sql.toByteArray(StandardCharsets.UTF_8)
    }

    // 요청 디코딩: 바이트 배열 → SQL 문자열
    fun decodeRequest(bytes: ByteArray): String {
        return bytes.toString(StandardCharsets.UTF_8)
    }

    // 응답 인코딩: DbResponse → JSON 바이트 배열
    fun encodeResponse(response: DbResponse): ByteArray {
        val jsonString = json.encodeToString(response)
        return jsonString.toByteArray(StandardCharsets.UTF_8)
    }

    // 응답 디코딩: JSON 바이트 배열 → DbResponse
    fun decodeResponse(bytes: ByteArray): DbResponse {
        val jsonString = bytes.toString(StandardCharsets.UTF_8)
        return json.decodeFromString(jsonString)
    }

    // 메시지 쓰기: 길이(4 byte) + 데이터
    fun writeMessage(output: OutputStream, data: ByteArray) {
        val dos = DataOutputStream(output)
        dos.writeInt(data.size)
        dos.write(data)
        dos.flush()
    }

    // 메시지 읽기: 길이 읽고 → 데이터 읽기
    fun readMessage(input: InputStream): ByteArray {
        val dis = DataInputStream(input)
        val length = dis.readInt()
        val data = ByteArray(length)
        dis.readFully(data)
        return data
    }
}
```

### 지원하는 SQL 명령

서버는 다음 SQL 명령을 파싱하고 처리합니다:

| 명령 | 형식 | 예시 |
|------|------|------|
| **CREATE TABLE** | `CREATE TABLE <name> (col type, ...)` | `CREATE TABLE users (id INT, name VARCHAR)` |
| **INSERT** | `INSERT INTO <table> VALUES (col="val", ...)` | `INSERT INTO users VALUES (id="1", name="John")` |
| **SELECT** | `SELECT * FROM <table> [WHERE ...]` | `SELECT * FROM users WHERE id="1"` |
| **DROP TABLE** | `DROP TABLE <name>` | `DROP TABLE users` |
| **EXPLAIN** | `EXPLAIN <query>` | `EXPLAIN SELECT * FROM users` |
| **PING** | `PING` | `PING` |

### ConnectionHandler SQL 파싱

```kotlin
private fun processRequest(sql: String): DbResponse {
    val trimmedSql = sql.trim().trimEnd(';')

    // PING 명령 처리
    if (trimmedSql.equals("PING", ignoreCase = true)) {
        return DbResponse(success = true, message = "pong")
    }

    return when {
        trimmedSql.startsWith("CREATE TABLE", ignoreCase = true)
            -> parseAndHandleCreateTable(trimmedSql)
        trimmedSql.startsWith("INSERT INTO", ignoreCase = true)
            -> parseAndHandleInsert(trimmedSql)
        trimmedSql.startsWith("SELECT", ignoreCase = true)
            -> parseAndHandleSelect(trimmedSql)
        trimmedSql.startsWith("DROP TABLE", ignoreCase = true)
            -> parseAndHandleDropTable(trimmedSql)
        trimmedSql.startsWith("EXPLAIN", ignoreCase = true)
            -> parseAndHandleExplain(trimmedSql)
        else -> DbResponse(
            success = false,
            message = "Unsupported SQL query: $sql",
            errorCode = 400
        )
    }
}
```

### 아키텍처 비교

#### 이전 (복잡한 계층)
```
Client: SQL → DbRequest(RAW_SQL) → JSON → TCP → Server
Server: TCP → JSON → DbRequest → SQL 추출 → 파싱 → 실행
```

#### 현재 (단순한 흐름)
```
Client: SQL → UTF-8 → TCP → Server
Server: TCP → UTF-8 → SQL → 파싱 → 실행
```

**제거된 파일:**
- `common/protocol/DbRequest.kt` - 요청 DTO
- `common/protocol/DbCommand.kt` - 명령 enum
- `api-server/util/SqlParser.kt` - API 서버의 SQL 파서 (db-server로 통합)

**변경된 파일:**
- `common/protocol/ProtocolCodec.kt` - String 인코딩/디코딩으로 단순화
- `db-server/ConnectionHandler.kt` - SQL 문자열 직접 처리
- `api-server/DbClient.kt` - SQL 문자열 직접 전송
- `api-server/TableController.kt` - DbRequest 생성 제거
