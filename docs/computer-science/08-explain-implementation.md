# EXPLAIN 기능 구현 — Elasticsearch 기반 Query Optimizer

## 개요

이 프로젝트에서는 **Elasticsearch를 시스템 카탈로그(System Catalog)** 로 활용해 Query Optimizer의 `EXPLAIN` 기능을 구현했습니다.

실제 테이블 데이터는 바이너리 `*.dat` 파일에 저장되고, Elasticsearch는 **메타데이터 저장소** 역할만 합니다.
- 테이블 스키마, 인덱스 정의, 컬럼 통계를 ES에 저장
- EXPLAIN 실행 시 이 메타데이터를 조회해 실행 계획(QueryPlan) 생성
- 생성된 실행 계획도 ES에 저장 (캐싱 및 히스토리)

---

## 1. 전체 아키텍처

```
TCP Client
    │  "EXPLAIN SELECT * FROM users WHERE email = 'a@b.com'"
    ▼
ConnectionHandler.processRequest()
    │  EXPLAIN 키워드 감지 → parseAndHandleExplain()
    ▼
ExplainService.explain(innerQuery)
    │
    ├─ SqlParser           파싱 (JSqlParser)
    ├─ TableMetadataService   ES: db-table-metadata
    ├─ TableStatisticsService ES: db-table-statistics
    ├─ IndexMetadataService   ES: db-index-metadata
    └─ QueryPlanRepository    ES: db-query-plans  (결과 저장)
    │
    ▼
DbResponse { success=true, data="{...queryPlan JSON...}" }
```

### 실제 데이터 저장 위치

| 데이터 | 저장 위치 |
|--------|-----------|
| 테이블 행 데이터 | `./data/*.dat` (바이너리 파일) |
| 테이블 스키마 | Elasticsearch `db-table-metadata` |
| 인덱스 정의 | Elasticsearch `db-index-metadata` |
| 컬럼 통계 | Elasticsearch `db-table-statistics` |
| 실행 계획 | Elasticsearch `db-query-plans` |
| 쿼리 로그 | Elasticsearch `db-query-logs` |

---

## 2. Elasticsearch 인덱스 구조 (5개)

### 2-1. `db-table-metadata` — 테이블 스키마

**파일**: `elasticsearch/document/TableMetadata.kt`

```kotlin
@Document(indexName = "db-table-metadata")
data class TableMetadata(
    @Id
    val tableId: String,          // "{tableName}_{uuid}"

    val tableName: String,        // 테이블명 (e.g. "users")

    val columns: List<ColumnDefinition>,  // 컬럼 목록

    val status: TableStatus,      // ACTIVE | DROPPED

    val estimatedRowCount: Long,  // 추정 행 수

    val createdAt: Instant,
    val updatedAt: Instant
)

data class ColumnDefinition(
    val name: String,             // 컬럼명
    val dataType: String,         // "INT", "VARCHAR", "BOOLEAN", ...
    val nullable: Boolean,
    val primaryKey: Boolean
)

enum class TableStatus { ACTIVE, DROPPED }
```

**언제 쓰이나?**
- `CREATE TABLE` → ES에 저장
- `DROP TABLE` → `status = DROPPED` 으로 소프트 삭제
- `INSERT` → `estimatedRowCount` 증가
- EXPLAIN → 테이블 존재 확인, `SELECT *` 시 컬럼 목록 조회

---

### 2-2. `db-index-metadata` — 인덱스 정의

**파일**: `elasticsearch/document/IndexMetadata.kt`

```kotlin
@Document(indexName = "db-index-metadata")
data class IndexMetadata(
    @Id
    val indexId: String,          // "{tableName}_{indexName}"

    val indexName: String,        // e.g. "idx_email"

    val tableName: String,        // e.g. "users"

    val indexColumns: List<String>, // 컬럼 목록 (순서 중요!)
                                    // e.g. ["name", "email", "age"]

    val indexType: IndexType,     // BTREE | HASH | FULLTEXT

    val unique: Boolean,

    val status: IndexStatus,      // ACTIVE | DROPPED

    val createdAt: Instant
)

enum class IndexType  { BTREE, HASH, FULLTEXT }
enum class IndexStatus { ACTIVE, DROPPED }
```

> **주의**: 현재 `CREATE INDEX` SQL 명령이 없습니다.
> 인덱스는 `IndexMetadataService.createIndex()`를 직접 호출하거나,
> 샘플 데이터 스크립트(`InitSampleDataForExplain`)로만 생성할 수 있습니다.

---

### 2-3. `db-table-statistics` — 컬럼 통계

**파일**: `elasticsearch/document/TableStatistics.kt`

```kotlin
@Document(indexName = "db-table-statistics")
data class TableStatistics(
    @Id
    val statsId: String,

    val tableName: String,

    val totalRows: Long,          // 총 행 수

    val columnStats: List<ColumnStat>  // 컬럼별 통계
)

data class ColumnStat(
    val columnName: String,

    val distinctCount: Long,      // 고유 값 개수 (카디널리티)
                                  // → selectivity = 1 / distinctCount

    val nullCount: Long,

    val minValue: String?,        // 최솟값 (문자열로 저장)

    val maxValue: String?,        // 최댓값

    val avgLength: Double?        // 평균 바이트 길이 (VARCHAR용)
)
```

**언제 갱신되나?**
- `CREATE TABLE` → 초기값(0)으로 생성
- `INSERT` → `totalRows++`, 각 컬럼의 `distinctCount++` (근사치)

---

### 2-4. `db-query-plans` — 실행 계획

**파일**: `elasticsearch/document/QueryPlan.kt`

```kotlin
@Document(indexName = "db-query-plans")
data class QueryPlan(
    @Id
    @Field(name = "plan_id", type = FieldType.Keyword)
    val planId: String,           // UUID

    @Field(name = "query_text", type = FieldType.Text)
    val queryText: String,        // 원본 SQL 문자열

    @Field(name = "query_hash", type = FieldType.Keyword)
    val queryHash: String,        // MD5(SQL) — 캐싱 키

    @Field(name = "execution_steps", type = FieldType.Nested)
    val executionSteps: List<ExecutionStep>,

    @Field(name = "estimated_cost", type = FieldType.Double)
    val estimatedCost: Double,    // 모든 Step 비용의 합

    @Field(name = "estimated_rows", type = FieldType.Long)
    val estimatedRows: Long,      // 마지막 Step의 예상 결과 행 수

    @Field(name = "is_covered_query", type = FieldType.Boolean)
    val isCoveredQuery: Boolean,  // Covered Index Scan 여부

    @Field(name = "generated_at", type = FieldType.Date)
    val generatedAt: Instant
)

data class ExecutionStep(
    val stepId: Int,
    val stepType: String,         // "TABLE_SCAN" | "INDEX_SCAN" | "COVERED_INDEX_SCAN"
    val tableName: String?,
    val indexUsed: String?,       // 사용된 인덱스명 (없으면 null)
    val filterCondition: String?, // WHERE 조건 문자열
    val columnsAccessed: List<String>,
    val estimatedCost: Double,
    val estimatedRows: Long,
    val isCovered: Boolean,
    val description: String       // 사람이 읽기 쉬운 설명
)
```

---

### 2-5. `db-query-logs` — 쿼리 실행 로그

**파일**: `elasticsearch/document/QueryLog.kt`

쿼리 타입(DDL/DML/DQL), 연결 ID, 실행 시간, 상태, 영향받은 테이블 등을 기록합니다.
EXPLAIN 분석보다는 운영 모니터링 용도입니다.

---

## 3. EXPLAIN 실행 흐름 (단계별 상세)

### 진입점: ConnectionHandler

```kotlin
// ConnectionHandler.kt
trimmedSql.startsWith("EXPLAIN", ignoreCase = true) -> parseAndHandleExplain(trimmedSql)

private fun parseAndHandleExplain(sql: String): DbResponse {
    // "EXPLAIN SELECT ..." → "SELECT ..."
    val innerQuery = sql.replace(Regex("^EXPLAIN\\s+", RegexOption.IGNORE_CASE), "").trim()

    val queryPlan = explainService!!.explain(innerQuery)

    val queryPlanJson = objectMapper.writeValueAsString(queryPlan)
    return DbResponse(success = true, message = "Query plan generated successfully", data = queryPlanJson)
}
```

---

### ExplainService.explain() — 9단계

**파일**: `elasticsearch/service/ExplainService.kt`

#### Step 1: SQL 파싱

```kotlin
val parsedQuery = sqlParser.parseQuery(sql)
// → ParsedQuery(
//       tableName = "users",
//       selectColumns = ["*"],
//       whereConditions = [WhereCondition(column="email", operator="=", value="'a@b.com'")],
//       orderBy = []
//   )
```

`SqlParser`는 JSqlParser(`CCJSqlParserUtil`)를 사용해 SQL을 AST로 변환한 뒤,
`ParsedQuery` 객체로 평탄화합니다.

`WhereCondition`은 AND로 연결된 조건들을 **리스트로 평탄화**합니다:
```kotlin
// WHERE age > 20 AND city = 'Seoul'
listOf(
    WhereCondition(column="age", operator=">", value="20"),
    WhereCondition(column="city", operator="=", value="'Seoul'")
)
```

#### Step 2: 테이블 존재 확인

```kotlin
if (!tableMetadataService.tableExists(tableName)) {
    throw IllegalStateException("Table doesn't exist: $tableName")
}
// ES 쿼리: db-table-metadata에서 tableName + status=ACTIVE 검색
```

#### Step 3: WHERE 절 분석 (현재 구현 한계)

```kotlin
// ⚠️ 현재 구현: 첫 번째 조건만 사용
val firstCondition = parsedQuery.whereConditions.firstOrNull()
val whereColumn = firstCondition?.column
val whereClause = if (firstCondition != null) {
    "${firstCondition.column} ${firstCondition.operator} ${firstCondition.value}"
} else ""
```

> WHERE 조건이 여러 개라도 **첫 번째 조건만** 인덱스 선택에 사용됩니다.

#### Step 4: SELECT 컬럼 해석

```kotlin
val selectColumns = if (parsedQuery.selectColumns.contains("*")) {
    // ES에서 컬럼 목록 조회
    tableMetadataService.getTableColumns(tableName)?.map { it.name } ?: emptyList()
} else {
    parsedQuery.selectColumns
}
```

#### Step 5: 테이블 통계 조회

```kotlin
// ES 쿼리: db-table-statistics
val stats = tableStatisticsService.getStatistics(tableName)
val totalRows = stats?.totalRows ?: 0
```

#### Step 6: 선택도(Selectivity) 계산

```kotlin
// TableStatisticsService.calculateSelectivity()
fun calculateSelectivity(tableName: String, columnName: String): Double {
    val stats = getStatistics(tableName) ?: return 1.0
    val columnStat = stats.columnStats.find { it.columnName == columnName }
    val distinctCount = columnStat?.distinctCount ?: 1L
    return if (distinctCount == 0L) 1.0 else 1.0 / distinctCount
}

// 예: email 컬럼의 distinctCount = 10000
// selectivity = 1 / 10000 = 0.0001 (0.01%)
```

**선택도의 의미**: 해당 WHERE 조건을 만족하는 행의 비율
- `selectivity = 0.0001` → 전체의 0.01%만 반환 → 인덱스 효과 큼
- `selectivity = 0.5` → 전체의 50% 반환 → 인덱스보다 풀스캔이 나을 수 있음

#### Step 7: 인덱스 사용 여부 결정

```kotlin
// IndexMetadataService.shouldUseIndexScan()
val decision = indexMetadataService.shouldUseIndexScan(tableName, whereColumn, selectivity)
```

**결정 로직** (`IndexMetadataService.kt`):

```
1. 인덱스 없음?
   → TABLE_SCAN ("No index available on column X")

2. selectivity > 0.15 (15% 초과)?
   → TABLE_SCAN ("High selectivity X% - Full table scan is faster")

3. WHERE 컬럼이 인덱스의 첫 번째 컬럼(leading column)이 아님?
   → TABLE_SCAN ("Column X is not the leading column in composite index")

4. 위 조건 모두 통과
   → INDEX_SCAN (선택된 인덱스: usableIndexes.first())
```

**결과 타입**:
```kotlin
data class IndexScanDecision(
    val useIndex: Boolean,
    val reason: String,
    val scanType: ScanType,            // TABLE_SCAN | INDEX_SCAN | INDEX_SEEK
    val selectedIndex: IndexMetadata?, // 선택된 인덱스 (null이면 풀스캔)
    val estimatedSelectivity: Double?
)
```

#### Step 8: Covered Index Scan 판단

```kotlin
// ExplainService.isCoveredQuery()
private fun isCoveredQuery(
    indexColumns: List<String>,  // 인덱스 컬럼 목록
    selectColumns: List<String>, // SELECT 컬럼 목록
    whereColumn: String?
): Boolean {
    // 조건 1: WHERE 컬럼이 인덱스의 첫 번째 컬럼이어야 함
    if (whereColumn != null && indexColumns.firstOrNull() != whereColumn) return false

    // 조건 2: SELECT 컬럼이 모두 인덱스에 포함되어야 함
    return selectColumns.all { selectCol ->
        indexColumns.any { it.equals(selectCol, ignoreCase = true) }
    }
}
```

**예시**:
```
인덱스: idx_name_email (name, email, age)

✅ COVERED:
  SELECT name, email FROM users WHERE name = 'Alice'
  → WHERE 컬럼(name) = leading column ✓
  → SELECT 컬럼(name, email) ⊂ 인덱스 컬럼 ✓

❌ NOT COVERED:
  SELECT name, email, address FROM users WHERE name = 'Alice'
  → address가 인덱스에 없음 → 테이블 접근 필요
```

#### Step 9: 비용 계산 및 QueryPlan 생성

**비용 공식**:

```kotlin
// TABLE_SCAN 비용
estimatedCost = totalRows.toDouble()
// (전체 행을 다 읽어야 하므로 행 수 = 비용)

// INDEX_SCAN / COVERED_INDEX_SCAN 비용
private fun calculateIndexScanCost(totalRows: Long, selectivity: Double): Double {
    val indexSeekCost = log2(totalRows.toDouble())  // B-tree 탐색 비용
    val dataReadCost = totalRows * selectivity       // 실제 읽을 행 수
    return indexSeekCost + dataReadCost
}

// 예: totalRows=100000, selectivity=0.0001
// indexSeekCost = log2(100000) ≈ 17
// dataReadCost = 100000 * 0.0001 = 10
// 총 비용 = 27  (vs TABLE_SCAN: 100000)
```

**QueryPlan 생성 및 저장**:

```kotlin
val queryPlan = QueryPlan(
    planId = UUID.randomUUID().toString(),
    queryText = sql,
    queryHash = MD5(sql),          // 동일 SQL → 같은 해시 (캐싱 키)
    executionSteps = executionSteps,
    estimatedCost = executionSteps.sumOf { it.estimatedCost },
    estimatedRows = executionSteps.lastOrNull()?.estimatedRows ?: 0,
    isCoveredQuery = executionSteps.any { it.isCovered },
    generatedAt = Instant.now()
)

// ES에 저장
queryPlanRepository.save(queryPlan)
```

---

## 4. 스캔 유형별 정리

| 스캔 유형 | 조건 | 비용 | 설명 |
|-----------|------|------|------|
| `TABLE_SCAN` | WHERE 없음, 인덱스 없음, 선택도 > 15% | `totalRows` | 전체 테이블 순차 읽기 |
| `INDEX_SCAN` | 인덱스 있음, 선택도 ≤ 15%, leading column | `log2(n) + n * s` | 인덱스 탐색 후 테이블 행 접근 |
| `COVERED_INDEX_SCAN` | INDEX_SCAN + SELECT 컬럼 전부 인덱스에 포함 | `log2(n) + n * s` | 테이블 접근 없이 인덱스만으로 완결 |

---

## 5. 실제 응답 예시

**요청**:
```
EXPLAIN SELECT name, email FROM users WHERE email = 'alice@example.com'
```

**응답 (DbResponse.data)**:
```json
{
  "planId": "a1b2c3d4-...",
  "queryText": "SELECT name, email FROM users WHERE email = 'alice@example.com'",
  "queryHash": "d41d8cd98f00b204e9800998ecf8427e",
  "executionSteps": [
    {
      "stepId": 1,
      "stepType": "COVERED_INDEX_SCAN",
      "tableName": "users",
      "indexUsed": "idx_email_name",
      "filterCondition": "email = 'alice@example.com'",
      "columnsAccessed": ["name", "email"],
      "estimatedCost": 27.4,
      "estimatedRows": 1,
      "isCovered": true,
      "description": "Using covering index 'idx_email_name' (selectivity: 0.001%). All columns can be retrieved from index without accessing table data. ✅ VERY EFFICIENT"
    }
  ],
  "estimatedCost": 27.4,
  "estimatedRows": 1,
  "isCoveredQuery": true,
  "generatedAt": "2026-02-19T10:00:00Z"
}
```

---

## 6. 클래스 다이어그램

```
ConnectionHandler
    │ uses
    ▼
ExplainService
    ├── SqlParser                  (SQL → ParsedQuery)
    ├── TableMetadataService       (ES: db-table-metadata)
    │       └── TableMetadataRepository
    ├── TableStatisticsService     (ES: db-table-statistics)
    │       └── TableStatisticsRepository
    ├── IndexMetadataService       (ES: db-index-metadata)
    │       └── IndexMetadataRepository
    └── QueryPlanRepository        (ES: db-query-plans)

도메인 객체:
    ParsedQuery        ← SqlParser 결과
    WhereCondition     ← WHERE 조건 (단순 data class)
    IndexScanDecision  ← 인덱스 사용 결정 결과
    QueryPlan          ← ES에 저장되는 실행 계획
    ExecutionStep      ← QueryPlan의 각 단계
```

---

## 7. 초기화 및 실행

```bash
# 1. Elasticsearch 5개 인덱스 생성
./gradlew :db-server:runInitElasticsearch

# 강제 재생성 (기존 데이터 삭제)
./gradlew :db-server:runInitElasticsearch -Pargs="--force"

# 2. 샘플 메타데이터 로드 (users/orders/products 테이블 + 인덱스 + 통계)
./gradlew :db-server:runInitSampleData

# 3. EXPLAIN 예제 실행
./gradlew :db-server:runExplainExample

# 4. 통합 테스트 (Docker로 ES 필요)
docker compose up -d elasticsearch
./gradlew integrationTest
```

---

## 8. 테스트 구조

### 단위 테스트 (Mockito, ES 불필요)

**파일**: `test/.../elasticsearch/service/ExplainServiceTest.kt`

```kotlin
// 인덱스가 있고 선택도가 낮을 때 → INDEX_SCAN
@Test
fun `low selectivity with index should use index scan`()

// 인덱스 없음 → TABLE_SCAN
@Test
fun `no index should use table scan`()

// SELECT 컬럼이 인덱스 포함 → COVERED_INDEX_SCAN
@Test
fun `all select columns in index should use covered index scan`()

// 존재하지 않는 테이블 → IllegalStateException
@Test
fun `non-existent table should throw exception`()
```

### 통합 테스트 (`@Tag("integration")`, `@Disabled`)

**파일**: `test/.../elasticsearch/ExplainIntegrationTest.kt`

```kotlin
@BeforeAll
fun setup() {
    // 실제 ES에 users 테이블 메타데이터 + 인덱스 2개 + 통계 생성
}

// INDEX_SCAN, COVERED_INDEX_SCAN, TABLE_SCAN 시나리오
// 비용 추정 (INDEX 비용 < TABLE 비용)
// QueryPlan 저장 및 planId로 재조회
// 동일 SQL → 동일 queryHash
```

---

## 9. 현재 구현의 한계 (Known Limitations)

| 한계 | 설명 |
|------|------|
| **WHERE 단일 조건** | AND/OR 다중 조건 중 첫 번째만 인덱스 선택에 사용 |
| **캐시 미활용** | `queryHash`로 캐시 조회 메서드(`getQueryPlanByHash`)는 있지만, `explain()`에서 캐시를 먼저 확인하지 않고 항상 새 계획 생성 |
| **CREATE INDEX 없음** | SQL로 인덱스를 생성할 수 없고 코드/스크립트로만 가능 |
| **실제 실행과 무관** | EXPLAIN은 계획만 보여줄 뿐, 실제 SELECT 실행 경로는 별도 (현재 SELECT는 항상 풀 파일 스캔) |
| **통계 정확도** | INSERT 시 `distinctCount`를 무조건 +1 증가시키는 근사치 방식 |
| **선택도 임계값 고정** | 15%가 코드에 하드코딩됨 (`selectivityThreshold = 0.15`) |

---

## 10. 미구현 사항 및 개선 과제

코드 분석 결과 발견된 구조적 문제와 향후 구현이 필요한 항목들입니다.

### 10-1. [Critical] DML 파이프라인과 ES 카탈로그의 단절

현재 가장 큰 구조적 문제입니다.

`TableService`와 `ConnectionHandler`는 ES 서비스를 **전혀 호출하지 않습니다**.
TCP로 `CREATE TABLE`, `INSERT`, `DROP TABLE`을 실행해도 Elasticsearch에는 아무 변화가 없습니다.

```
현재 상태:

TCP "CREATE TABLE foo (...)"
    → ConnectionHandler.parseAndHandleCreateTable()
    → TableService.createTable()
    → TableFileManager.writeTable()       ← ./data/foo.dat 생성
    → (끝. ES 업데이트 없음)

TCP "EXPLAIN SELECT * FROM foo"
    → ExplainService.explain()
    → TableMetadataService.tableExists("foo")  ← ES 조회
    → false (ES에 foo가 없으므로)
    → IllegalStateException: "Table doesn't exist: foo"  ← 실패!
```

**결과**: EXPLAIN은 샘플 스크립트(`InitSampleDataForExplain`)로 미리 넣어둔 테이블에서만 동작합니다. 사용자가 TCP로 직접 만든 테이블에 대해서는 항상 실패합니다.

**필요한 작업**: `ConnectionHandler`(또는 `TableService`)에서 DDL/DML 처리 시 ES 서비스를 함께 호출하는 브릿지 코드 추가:

```
필요한 호출 매핑:

CREATE TABLE → TableMetadataService.createTable()
               TableStatisticsService.initializeStatistics()

INSERT       → TableMetadataService.updateRowCount(tableName, +1)
               TableStatisticsService.updateStatisticsOnInsert()

DROP TABLE   → TableMetadataService.dropTable()
               TableStatisticsService.deleteStatistics()
```

ES 서비스 메서드들(`createTable()`, `updateRowCount()`, `initializeStatistics()`, `updateStatisticsOnInsert()`, `dropTable()`, `deleteStatistics()`)은 이미 구현되어 있으나 호출하는 곳이 없어 **데드 코드** 상태입니다.

---

### 10-2. [Critical] ES 장애 시 그레이스풀 디그레이데이션 부재

`ConnectionHandler`에서 `explainService`는 nullable로 선언되어 있고, null이면 503 응답을 반환하는 가드가 있습니다:

```kotlin
// ConnectionHandler.kt
private val explainService: ExplainService? = null

if (explainService == null) {
    return DbResponse(success = false, message = "ExplainService not configured", errorCode = 503)
}
```

그러나 `DbServerApplication`에서 `ExplainService`는 Spring Bean으로 **필수 주입**됩니다.
ES가 다운된 상태에서 애플리케이션을 시작하면 `ElasticsearchRepository` 빈 생성이 실패하여 **앱 전체가 기동 불가**합니다.

```
ES 다운 → Spring Context 초기화 실패
       → BeanCreationException
       → 앱 기동 실패 (EXPLAIN만 안 되는 게 아니라 전부 안 됨)
```

**필요한 작업**:
- `ExplainService`에 `@ConditionalOnBean(ElasticsearchOperations::class)` 또는 `@ConditionalOnProperty` 적용
- 또는 ES 관련 빈들을 별도 `@Configuration`에 묶어 조건부 로드
- ES 없이도 기본 DB 기능(CREATE/INSERT/SELECT/DROP)은 정상 동작하도록 분리

---

### 10-3. [Medium] QueryLogService — 완전한 데드 코드

`QueryLogService`는 `@Service`로 등록되어 있고 `indexQueryLog()`, `searchQueryLogs()`, `getQueryStatistics()` 메서드를 갖고 있지만, **어떤 핸들러에서도 호출하지 않습니다**.

`db-query-logs` ES 인덱스는 생성되지만 실제 데이터가 한 건도 기록되지 않습니다.

**필요한 작업**: `ConnectionHandler`에서 각 SQL 처리 후 `QueryLogService.indexQueryLog()` 호출:

```kotlin
// 모든 SQL 처리 후
queryLogService.indexQueryLog(QueryLog(
    queryType = QueryType.DQL,        // DDL, DML, DQL 구분
    queryText = sql,
    connectionId = connectionId,
    executionTimeMs = elapsed,
    status = "SUCCESS" or "FAILED",
    affectedTables = listOf(tableName)
))
```

---

### 10-4. [Medium] Plan Cache 미활용

`explain()` 메서드는 매 호출마다 새 `QueryPlan`을 생성하고 저장합니다. `queryHash`(MD5)로 캐시를 조회하는 `getQueryPlanByHash()` 메서드가 있지만, `explain()` 내부에서 호출하지 않습니다.

```kotlin
// 현재 흐름:
fun explain(sql: String): QueryPlan {
    // ... 항상 새로 생성
    val queryPlan = QueryPlan(...)
    return queryPlanRepository.save(queryPlan)
}

// 기대하는 흐름:
fun explain(sql: String): QueryPlan {
    val hash = generateQueryHash(sql)

    // 캐시 히트 → 바로 반환
    val cached = queryPlanRepository.findByQueryHash(hash)
    if (cached != null) return cached

    // 캐시 미스 → 새로 생성 후 저장
    val queryPlan = QueryPlan(...)
    return queryPlanRepository.save(queryPlan)
}
```

**고려 사항**: 캐시 무효화 정책이 필요합니다. 테이블 통계나 인덱스가 변경되면 해당 테이블의 캐시된 Plan은 무효가 되어야 합니다.

---

### 10-5. [Medium] OR 조건의 의미론적 오류

`SqlParser.extractConditions()`는 `AND`와 `OR` 조건을 모두 평탄화하여 `List<WhereCondition>`에 넣습니다.
`ExplainService`는 이 리스트의 **첫 번째 조건만** 인덱스 선택에 사용합니다.

**AND의 경우**: 첫 번째 조건으로 인덱스를 선택하는 것은 합리적인 근사치입니다.
인덱스로 1차 필터링 후 나머지 조건을 순차 적용하면 되기 때문입니다.

**OR의 경우**: 의미론적으로 잘못됩니다.
`WHERE email = 'a@b.com' OR age > 50`에서 email 인덱스를 사용하더라도, `age > 50`인 행은 인덱스와 무관하게 전체 스캔이 필요합니다.

```sql
-- 이 쿼리에서 email 인덱스만 쓸 수 없음
SELECT * FROM users WHERE email = 'a@b.com' OR age > 50

-- 실제로는 두 가지 방법:
-- 1) TABLE_SCAN (가장 정직한 선택)
-- 2) Index Merge: idx_email + idx_age 결과를 UNION (고급)
```

**필요한 작업**:
- `ParsedQuery`에 `AND` / `OR` 구분을 보존 (현재는 평탄화로 사라짐)
- OR 조건이 있으면 `TABLE_SCAN`으로 fallback 하거나, Index Merge 전략 구현

---

### 10-6. [Medium] CREATE INDEX / DROP INDEX SQL 명령 추가

현재 인덱스는 코드(`IndexMetadataService.createIndex()`)로만 생성할 수 있습니다.
TCP 클라이언트에서 인덱스를 관리하려면 SQL 명령이 필요합니다.

```sql
-- 필요한 명령어
CREATE INDEX idx_email ON users (email)
CREATE UNIQUE INDEX idx_email ON users (email)
CREATE INDEX idx_name_age ON users (name, age)   -- 복합 인덱스
DROP INDEX idx_email ON users
```

**필요한 작업**:
- `SqlParser`에 `CREATE INDEX` / `DROP INDEX` 파싱 추가 (JSqlParser가 지원)
- `ConnectionHandler`에 라우팅 추가
- `IndexMetadataService.createIndex()` / `dropIndex()` 호출

---

### 10-7. [Medium] SqlParser의 미지원 구문

`SqlParser.parseQuery()`는 현재 **단일 테이블 SELECT만** 지원합니다.

| 구문 | 상태 | 비고 |
|------|------|------|
| `SELECT col FROM table` | 지원 | |
| `WHERE col = / != / > / >= / < / <=` | 지원 | |
| `WHERE col BETWEEN x AND y` | 지원 | |
| `WHERE col LIKE 'pattern'` | 지원 | |
| `WHERE col IS NULL / IS NOT NULL` | 지원 | |
| `AND`, `OR` | 파싱됨 | 하지만 OR 의미론 미반영 (10-5 참조) |
| `WHERE col IN (v1, v2)` | 미지원 | 코드에 `// TODO: IN 연산자 (후순위)` 주석. 조건이 누락됨 |
| `JOIN` | 미지원 | `PlainSelect` 단일 테이블 가정 |
| `GROUP BY` / `HAVING` | 미지원 | JSqlParser가 파싱하지만 `ParsedQuery`에 필드 없음 |
| `UNION` / `INTERSECT` | 미지원 | `PlainSelect` 검증에서 거부됨 |
| Subquery | 미지원 | |

**IN 연산자 문제**: `WHERE status IN ('ACTIVE', 'PENDING')`에서 `IN` 조건이 파싱 중 **조용히 무시**됩니다 (예외도 발생하지 않음). EXPLAIN 결과에서 WHERE 절이 있는데 `filterCondition = null`로 나오는 혼란이 발생할 수 있습니다.

---

### 10-8. [Low] 통계 정확도 — distinctCount 증분 방식

현재 `updateStatisticsOnInsert()`는 매 INSERT마다 `distinctCount`를 무조건 +1 합니다:

```kotlin
// 현재 구현 (부정확)
colStat.copy(distinctCount = colStat.distinctCount + 1)
// "Seoul" 10번 INSERT → distinctCount가 10 증가 (실제 고유값은 1)
```

**결과**: 낮은 카디널리티 컬럼(예: `gender`, `status`)의 선택도가 실제보다 훨씬 낮게 추정되어, 불필요한 INDEX_SCAN을 선택할 수 있습니다.

**개선 방안**:
1. **HyperLogLog**: 확률적 카디널리티 추정 (메모리 효율적, ±2% 오차)
2. **주기적 재수집**: MySQL의 `ANALYZE TABLE`처럼 전체 데이터를 스캔해 정확한 통계 재계산
3. **하이브리드**: 증분 업데이트 + N건마다 정확한 재수집

```sql
-- 향후 추가할 명령어
ANALYZE TABLE users    -- 통계 재수집
```

---

### 10-9. [Low] 비용 모델 개선

현재 비용 모델은 단순합니다:

| 항목 | 현재 | 실제 DB(PostgreSQL 등) |
|------|------|----------------------|
| TABLE_SCAN 비용 | `totalRows` | `seq_page_cost * pages + cpu_tuple_cost * rows` |
| INDEX_SCAN 비용 | `log2(n) + n*s` | `random_page_cost * pages + cpu_index_tuple_cost * rows` |
| COVERED vs NON-COVERED | 비용 동일 | COVERED가 `random_page_cost` 절약 |
| I/O 비용 / CPU 비용 분리 | 미분리 | 분리 (random vs sequential I/O 가중치 차등) |
| 버퍼 풀 히트율 반영 | 미반영 | `effective_cache_size` 기반 보정 |

**개선 방향**:
- COVERED_INDEX_SCAN은 테이블 접근이 없으므로 `dataReadCost`를 0으로 해야 함
- Sequential I/O(TABLE_SCAN)와 Random I/O(INDEX_SCAN)의 비용 가중치 차등 적용
- 버퍼 풀(이미 구현된 `BufferPool`)의 캐시 히트율을 비용 계산에 반영

---

### 10-10. [Low] EXPLAIN과 실제 SELECT 실행 경로 통합

현재 EXPLAIN이 생성한 실행 계획과 실제 SELECT 실행은 **완전히 별개**입니다:

```
EXPLAIN SELECT * FROM users WHERE email = 'a@b.com'
→ "COVERED_INDEX_SCAN using idx_email" (ES 기반 계획)

SELECT * FROM users WHERE email = 'a@b.com'
→ Full file scan on ./data/users.dat (항상 전체 파일 읽기)
```

궁극적으로 EXPLAIN이 생성한 계획을 실제 SELECT 실행에서 활용해야 의미가 있습니다:

```
향후 목표:

SELECT 실행 요청
    → SqlParser.parseQuery()
    → ExplainService.explain()    ← 실행 계획 생성
    → ExecutionEngine.execute(plan)  ← 계획대로 실행
        → plan이 INDEX_SCAN이면 인덱스 파일에서 탐색
        → plan이 TABLE_SCAN이면 전체 파일 스캔
    → 결과 반환
```

이를 위해서는 **인덱스 파일 자체의 구현** (B-tree 파일 구조)이 선행되어야 합니다.

---

## 11. 구현 우선순위 로드맵

| 순서 | 작업 | 근거 |
|------|------|------|
| 1 | DML ↔ ES 카탈로그 브릿지 (10-1) | EXPLAIN이 동적 테이블에서 동작하지 않는 근본 원인 |
| 2 | ES 장애 시 그레이스풀 디그레이데이션 (10-2) | ES 없이도 기본 DB 기능이 동작해야 함 |
| 3 | CREATE INDEX / DROP INDEX SQL (10-6) | 인덱스를 SQL로 관리할 수 없으면 EXPLAIN 테스트가 불편 |
| 4 | Plan Cache 활용 (10-4) | 이미 코드가 있으므로 연결만 하면 됨 |
| 5 | OR 조건 처리 (10-5) | 의미론적 정확성 |
| 6 | QueryLogService 연결 (10-3) | 모니터링/분석 기반 |
| 7 | IN 연산자 지원 (10-7) | 자주 쓰이는 SQL 구문 |
| 8 | 통계 정확도 개선 (10-8) | 장기적 정확성 |
| 9 | 비용 모델 고도화 (10-9) | 실무 수준 근접 |
| 10 | EXPLAIN ↔ SELECT 통합 (10-10) | 최종 목표. 인덱스 파일 구현 필요 |

---

## 12. 학습 포인트 요약

| 개념 | 이 프로젝트에서의 구현 | 상태 |
|------|----------------------|------|
| **System Catalog** | Elasticsearch 5개 인덱스로 구현 | 구조 완성, DML 연동 미완 |
| **Selectivity** | `1 / distinctCount` 로 계산 | 동작하나 정확도 낮음 |
| **Index vs Table Scan** | 15% 임계값 + leading column 규칙 | 단일 조건만 지원 |
| **Covered Index** | SELECT 컬럼 ⊂ 인덱스 컬럼 | 판단 로직 완성 |
| **Cost Model** | TABLE: O(n), INDEX: O(log n + n·s) | I/O/CPU 미분리 |
| **Plan Cache** | MD5 해시 기반 | 저장만 되고 조회 미활용 |
| **Query Logging** | QueryLogService 구현 | 핸들러 연결 없음 (데드 코드) |
| **Graceful Degradation** | null 가드 존재 | ES 장애 시 앱 전체 기동 불가 |

---

## 참고 자료

- [05-query-optimization.md](./05-query-optimization.md) — 비용 기반 최적화 개념
- [06-statistics-and-metadata.md](./06-statistics-and-metadata.md) — 통계 & 메타데이터 개념
- [EXPLAIN_GUIDE.md](../../GUIDE/EXPLAIN_GUIDE.md) — 실행 가이드
- `db-server/src/main/kotlin/study/db/server/elasticsearch/` — 구현 소스
