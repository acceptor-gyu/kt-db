# EXPLAIN 기능 구현기 — Elasticsearch를 System Catalog로 활용한 Query Optimizer 만들기

---

## 목차

1. [들어가며](#1-들어가며)
2. [왜 EXPLAIN인가](#2-왜-explain인가)
3. [설계: Elasticsearch를 System Catalog로](#3-설계-elasticsearch를-system-catalog로)
   - 3-1. System Catalog이란
   - 3-2. Elasticsearch 인덱스 설계
   - 3-3. 전체 아키텍처
4. [구현: EXPLAIN 실행 흐름 9단계](#4-구현-explain-실행-흐름-9단계)
   - 4-1. SQL 파싱
   - 4-2. 테이블 존재 확인
   - 4-3. WHERE 절 분석
   - 4-4. SELECT 컬럼 해석
   - 4-5. 통계 조회
   - 4-6. 선택도 계산
   - 4-7. 인덱스 사용 여부 결정
   - 4-8. Covered Index 판단
   - 4-9. 비용 계산 및 QueryPlan 생성
5. [스캔 유형 비교](#5-스캔-유형-비교)
6. [실제 응답 살펴보기](#6-실제-응답-살펴보기)
7. [구현하면서 배운 것](#7-구현하면서-배운-것)
8. [한계와 남은 과제](#8-한계와-남은-과제)
9. [마무리](#9-마무리)

---

## 1. 들어가며

파일 기반 데이터베이스를 직접 만들고 있다. Kotlin과 Spring Boot로 TCP 서버를 구성하고, 테이블 데이터를 바이너리 파일(`*.dat`)에 저장하는 구조다. CREATE TABLE, INSERT, SELECT, DROP TABLE 같은 기본적인 SQL 명령은 이미 동작한다.

그런데 한 가지 궁금증이 생겼다. MySQL에서 쿼리 앞에 `EXPLAIN`을 붙이면 옵티마이저가 어떤 인덱스를 쓸지, 몇 행을 읽을지, 비용이 얼마인지를 보여준다. **이걸 직접 만들어보면 어떨까?**

이 글은 그 과정을 기록한 것이다.

---

## 2. 왜 EXPLAIN인가

데이터베이스를 사용하다 보면 느린 쿼리를 만나게 된다. 이때 가장 먼저 하는 일이 `EXPLAIN`이다.

```sql
EXPLAIN SELECT * FROM users WHERE email = 'alice@example.com';
```

이 한 줄이 알려주는 정보는 많다.

- **어떤 스캔 방식을 쓰는가**: 전체 테이블을 읽는가(TABLE_SCAN), 인덱스를 타는가(INDEX_SCAN)
- **왜 그 방식을 선택했는가**: 인덱스가 없어서? 선택도가 높아서? Leading column이 아니라서?
- **비용은 얼마인가**: 숫자로 비교할 수 있다
- **몇 행을 읽을 것으로 예상하는가**: 실제 실행 전에 미리 알 수 있다

실무에서는 이 결과를 읽기만 했다. 하지만 직접 만들어보면 각 숫자가 **어디서 오는 건지**, 옵티마이저가 **어떤 논리로 판단하는 건지** 체감할 수 있을 거라 생각했다.

---

## 3. 설계: Elasticsearch를 System Catalog로

### 3-1. System Catalog이란

실제 데이터베이스에는 **System Catalog**이라는 내부 저장소가 있다. MySQL의 `INFORMATION_SCHEMA`, PostgreSQL의 `pg_catalog`이 그것이다.

System Catalog에는 다음과 같은 정보가 담긴다.

- 어떤 테이블이 있는지, 각 테이블에 어떤 컬럼이 있는지 (스키마)
- 어떤 인덱스가 정의되어 있는지, 어떤 컬럼에 걸려 있는지
- 각 컬럼의 고유값 개수(카디널리티), NULL 비율, 최솟값/최댓값 (통계)

옵티마이저는 **실제 데이터를 읽지 않고** 이 카탈로그만 보고 실행 계획을 세운다. 이것이 EXPLAIN이 빠른 이유다.

이 프로젝트에서는 Elasticsearch를 System Catalog으로 활용하기로 했다. ES의 문서 기반 저장 구조가 메타데이터를 JSON으로 관리하기에 적합했고, Spring Data Elasticsearch와의 통합도 간편했다.

### 3-2. Elasticsearch 인덱스 설계

총 5개의 ES 인덱스를 설계했다.

#### `db-table-metadata` — 테이블 스키마

테이블명, 컬럼 목록(이름, 타입, nullable, PK 여부), 상태(ACTIVE/DROPPED), 추정 행 수를 저장한다.

```kotlin
data class TableMetadata(
    val tableId: String,              // "{tableName}_{uuid}"
    val tableName: String,
    val columns: List<ColumnDefinition>,
    val status: TableStatus,          // ACTIVE | DROPPED
    val estimatedRowCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)
```

`CREATE TABLE` 시 생성되고, `INSERT` 시 `estimatedRowCount`가 증가하며, `DROP TABLE` 시 `status = DROPPED`로 소프트 삭제된다.

#### `db-index-metadata` — 인덱스 정의

인덱스명, 대상 테이블, 인덱스 컬럼 목록(순서 중요), 인덱스 타입(BTREE/HASH/FULLTEXT), unique 여부를 저장한다.

```kotlin
data class IndexMetadata(
    val indexId: String,              // "{tableName}_{indexName}"
    val indexName: String,
    val tableName: String,
    val indexColumns: List<String>,   // 순서가 중요하다
    val indexType: IndexType,         // BTREE | HASH | FULLTEXT
    val unique: Boolean,
    val status: IndexStatus,
    val createdAt: Instant
)
```

여기서 `indexColumns`의 **순서**가 핵심이다. 복합 인덱스 `(name, email, age)`에서 WHERE 조건이 `email = ?`이면, email은 두 번째 컬럼이므로 이 인덱스를 활용할 수 없다. 첫 번째 컬럼(leading column)이 WHERE 조건에 있어야 한다. 이것이 실제 DB에서도 동일하게 적용되는 **Leftmost Prefix Rule**이다.

#### `db-table-statistics` — 컬럼 통계

테이블별 총 행 수와 컬럼별 통계(고유값 개수, NULL 수, 최솟값, 최댓값, 평균 길이)를 저장한다.

```kotlin
data class ColumnStat(
    val columnName: String,
    val distinctCount: Long,    // 카디널리티
    val nullCount: Long,
    val minValue: String?,
    val maxValue: String?,
    val avgLength: Double?      // VARCHAR용
)
```

이 중 `distinctCount`(카디널리티)가 가장 중요하다. 이 값으로 **선택도(Selectivity)**를 계산하고, 선택도가 인덱스 사용 여부를 결정하기 때문이다.

#### `db-query-plans` — 실행 계획

생성된 QueryPlan을 저장한다. `queryHash`(SQL의 MD5)를 키로 동일한 쿼리의 실행 계획을 캐싱할 수 있도록 설계했다.

#### `db-query-logs` — 쿼리 로그

쿼리 타입, 실행 시간, 상태, 영향받은 테이블 등을 기록하는 운영 모니터링용 인덱스다.

### 3-3. 전체 아키텍처

```
TCP Client
    |  "EXPLAIN SELECT * FROM users WHERE email = 'a@b.com'"
    v
ConnectionHandler.processRequest()
    |  EXPLAIN 키워드 감지
    v
ExplainService.explain(innerQuery)
    |
    +-- SqlParser              SQL 파싱 (JSqlParser)
    +-- TableMetadataService   ES: 스키마 조회
    +-- TableStatisticsService ES: 통계 조회
    +-- IndexMetadataService   ES: 인덱스 조회 + 스캔 결정
    +-- QueryPlanRepository    ES: 결과 저장
    |
    v
DbResponse { success=true, data="{...queryPlan JSON...}" }
```

TCP 클라이언트가 `EXPLAIN`으로 시작하는 SQL을 보내면, `ConnectionHandler`가 이를 감지해 `ExplainService`로 위임한다. `ExplainService`는 ES에서 메타데이터를 조회하고, 실행 계획을 생성해 JSON으로 반환한다.

---

## 4. 구현: EXPLAIN 실행 흐름 9단계

`ExplainService.explain()` 메서드가 하나의 SQL을 받아 QueryPlan을 반환하기까지 9단계를 거친다.

### 4-1. SQL 파싱

JSqlParser(`CCJSqlParserUtil`)를 사용해 SQL 문자열을 AST(Abstract Syntax Tree)로 변환한 뒤, 프로젝트에서 사용하는 `ParsedQuery` 객체로 평탄화한다.

```kotlin
val parsedQuery = sqlParser.parseQuery(sql)
// ParsedQuery(
//     tableName = "users",
//     selectColumns = ["*"],
//     whereConditions = [WhereCondition(column="email", operator="=", value="'a@b.com'")],
//     orderBy = []
// )
```

WHERE 절이 여러 조건을 가지면 AND/OR에 관계없이 리스트로 평탄화한다.

```kotlin
// WHERE age > 20 AND city = 'Seoul'
listOf(
    WhereCondition(column="age", operator=">", value="20"),
    WhereCondition(column="city", operator="=", value="'Seoul'")
)
```

지원하는 연산자는 `=`, `!=`, `>`, `>=`, `<`, `<=`, `BETWEEN`, `LIKE`, `IS NULL`, `IS NOT NULL`이다.

### 4-2. 테이블 존재 확인

ES의 `db-table-metadata` 인덱스에서 해당 테이블이 존재하는지, 상태가 ACTIVE인지 확인한다.

```kotlin
if (!tableMetadataService.tableExists(tableName)) {
    throw IllegalStateException("Table doesn't exist: $tableName")
}
```

테이블이 없으면 여기서 바로 예외가 발생한다. 실제 DB에서 `EXPLAIN`을 존재하지 않는 테이블에 대해 실행하면 에러가 나는 것과 동일한 동작이다.

### 4-3. WHERE 절 분석

파싱된 WHERE 조건 중 **첫 번째 조건**을 추출한다.

```kotlin
val firstCondition = parsedQuery.whereConditions.firstOrNull()
val whereColumn = firstCondition?.column
```

현재 구현에서는 첫 번째 조건만 인덱스 선택에 사용한다. 다중 조건 최적화는 아직 미구현이다. 실제 DB 옵티마이저는 모든 조건을 분석해 최적의 인덱스를 선택하지만, 학습 목적으로 단순화한 부분이다.

### 4-4. SELECT 컬럼 해석

`SELECT *`인 경우 ES에서 테이블의 전체 컬럼 목록을 조회해 실제 컬럼명으로 치환한다.

```kotlin
val selectColumns = if (parsedQuery.selectColumns.contains("*")) {
    tableMetadataService.getTableColumns(tableName)?.map { it.name } ?: emptyList()
} else {
    parsedQuery.selectColumns
}
```

이 정보는 나중에 **Covered Index** 판단에 사용된다. SELECT하는 컬럼이 모두 인덱스에 포함되면 테이블 데이터에 접근할 필요가 없기 때문이다.

### 4-5. 통계 조회

ES의 `db-table-statistics` 인덱스에서 테이블의 총 행 수와 컬럼별 통계를 가져온다.

```kotlin
val stats = tableStatisticsService.getStatistics(tableName)
val totalRows = stats?.totalRows ?: 0
```

통계가 없으면 `totalRows = 0`으로 처리되어 비용이 0으로 계산된다. 실제 DB에서도 통계가 오래되거나 없으면 옵티마이저가 잘못된 판단을 내리는 경우가 있는데, 같은 원리다.

### 4-6. 선택도(Selectivity) 계산

선택도는 WHERE 조건을 만족하는 행의 비율을 추정한 값이다. 이것이 인덱스 사용 여부를 결정하는 가장 중요한 지표다.

```kotlin
fun calculateSelectivity(tableName: String, columnName: String): Double {
    val stats = getStatistics(tableName) ?: return 1.0
    val columnStat = stats.columnStats.find { it.columnName == columnName }
    val distinctCount = columnStat?.distinctCount ?: 1L
    return if (distinctCount == 0L) 1.0 else 1.0 / distinctCount
}
```

공식은 단순하다: **selectivity = 1 / distinctCount**

구체적인 예를 들어보자.

- `email` 컬럼: 10,000개의 고유값 → selectivity = 0.0001 (0.01%)
  - 의미: `WHERE email = ?`로 검색하면 전체의 0.01%만 해당
  - 인덱스를 타면 극소수의 행만 읽으면 되므로 매우 효율적

- `gender` 컬럼: 2개의 고유값 → selectivity = 0.5 (50%)
  - 의미: `WHERE gender = ?`로 검색하면 전체의 50%가 해당
  - 절반을 읽어야 하므로 인덱스를 타는 것보다 풀스캔이 나을 수 있음

이것이 "카디널리티가 높은 컬럼에 인덱스를 걸어라"는 실무 조언의 수학적 근거다.

### 4-7. 인덱스 사용 여부 결정

`IndexMetadataService.shouldUseIndexScan()`에서 4가지 규칙으로 판단한다.

```
1. 해당 컬럼에 인덱스가 없다
   → TABLE_SCAN ("No index available on column X")

2. selectivity > 0.15 (15% 초과)
   → TABLE_SCAN ("High selectivity X% - Full table scan is faster")

3. WHERE 컬럼이 인덱스의 첫 번째 컬럼(leading column)이 아니다
   → TABLE_SCAN ("Column X is not the leading column in composite index")

4. 위 조건 모두 통과
   → INDEX_SCAN (선택된 인덱스 반환)
```

**15% 임계값**에 대해 좀 더 설명하자면, 이는 실제 DB에서도 비슷한 기준을 사용한다. Random I/O(인덱스로 행을 하나씩 찾아가는 것)는 Sequential I/O(테이블 전체를 순차 읽기)보다 비용이 높다. 읽어야 할 행이 전체의 15%를 넘으면, Random I/O의 누적 비용이 Sequential I/O를 초과하게 되는 것이다.

결과는 `IndexScanDecision` 객체로 반환된다.

```kotlin
data class IndexScanDecision(
    val useIndex: Boolean,
    val reason: String,               // 결정 이유 (사람이 읽을 수 있는 설명)
    val scanType: ScanType,           // TABLE_SCAN | INDEX_SCAN | INDEX_SEEK
    val selectedIndex: IndexMetadata?,
    val estimatedSelectivity: Double?
)
```

### 4-8. Covered Index 판단

INDEX_SCAN으로 결정된 경우, 한 단계 더 나아가 **Covered Index Scan**이 가능한지 판단한다.

```kotlin
private fun isCoveredQuery(
    indexColumns: List<String>,
    selectColumns: List<String>,
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

Covered Index Scan이란 **인덱스만으로 쿼리 결과를 반환**하는 것이다. 보통 INDEX_SCAN은 인덱스에서 행의 위치를 찾은 뒤 테이블 데이터에 다시 접근해야 한다. 하지만 SELECT하는 컬럼이 모두 인덱스에 포함되어 있다면, 그 추가 접근이 필요 없다.

구체적인 예를 보자.

```
인덱스: idx_name_email (name, email, age)

-- Covered Index Scan 가능
SELECT name, email FROM users WHERE name = 'Alice'
  → WHERE 컬럼(name) = leading column
  → SELECT 컬럼(name, email) 모두 인덱스에 포함

-- Covered Index Scan 불가
SELECT name, email, address FROM users WHERE name = 'Alice'
  → address가 인덱스에 없음 → 테이블 데이터 접근 필요
```

MySQL의 `EXPLAIN`에서 `Extra` 컬럼에 `Using index`라고 뜨는 것이 바로 이 Covered Index Scan이다.

### 4-9. 비용 계산 및 QueryPlan 생성

마지막 단계에서 비용을 계산하고, 모든 정보를 하나의 QueryPlan으로 묶어 ES에 저장한다.

**비용 공식:**

```kotlin
// TABLE_SCAN: 전체 행을 읽어야 하므로 행 수 자체가 비용
estimatedCost = totalRows.toDouble()

// INDEX_SCAN / COVERED_INDEX_SCAN
fun calculateIndexScanCost(totalRows: Long, selectivity: Double): Double {
    val indexSeekCost = log2(totalRows.toDouble())   // B-tree 탐색 비용
    val dataReadCost = totalRows * selectivity         // 실제 읽을 행 수
    return indexSeekCost + dataReadCost
}
```

B-tree의 탐색 비용이 `log₂(n)`인 이유는, B-tree가 균형 이진 트리처럼 동작해 깊이가 `log₂(n)`이기 때문이다. 10만 행이면 약 17번의 비교로 원하는 위치를 찾을 수 있다.

**구체적인 계산 예시:**

```
totalRows = 100,000
selectivity = 0.0001 (email 컬럼, 고유값 10,000개)

TABLE_SCAN 비용:
  = 100,000

INDEX_SCAN 비용:
  indexSeekCost = log₂(100,000) ≈ 17
  dataReadCost  = 100,000 × 0.0001 = 10
  총 비용 = 17 + 10 = 27

비율: 100,000 / 27 ≈ 3,700배 차이
```

인덱스의 위력이 숫자로 드러나는 순간이다. 옵티마이저는 이 비용 비교를 통해 어떤 스캔 방식이 더 나은지 판단한다.

최종적으로 QueryPlan을 생성하고 ES에 저장한다.

```kotlin
val queryPlan = QueryPlan(
    planId = UUID.randomUUID().toString(),
    queryText = sql,
    queryHash = MD5(sql),              // 동일 SQL → 같은 해시 (캐싱 키)
    executionSteps = executionSteps,
    estimatedCost = executionSteps.sumOf { it.estimatedCost },
    estimatedRows = executionSteps.lastOrNull()?.estimatedRows ?: 0,
    isCoveredQuery = executionSteps.any { it.isCovered },
    generatedAt = Instant.now()
)

queryPlanRepository.save(queryPlan)
```

---

## 5. 스캔 유형 비교

구현한 세 가지 스캔 유형을 정리하면 다음과 같다.

| 스캔 유형 | 언제 선택되는가 | 비용 공식 | 설명 |
|-----------|----------------|-----------|------|
| **TABLE_SCAN** | WHERE 없음, 인덱스 없음, 선택도 > 15% | `totalRows` | 전체 테이블을 처음부터 끝까지 순차 읽기. 가장 단순하지만 대량 데이터에서는 느리다. |
| **INDEX_SCAN** | 인덱스 있음, 선택도 ≤ 15%, leading column 일치 | `log₂(n) + n × s` | 인덱스에서 조건에 맞는 행의 위치를 찾고, 테이블 데이터에 접근해 나머지 컬럼을 가져온다. |
| **COVERED_INDEX_SCAN** | INDEX_SCAN 조건 + SELECT 컬럼 전부 인덱스에 포함 | `log₂(n) + n × s` | 인덱스만으로 결과를 반환. 테이블 접근이 없어 가장 효율적이다. |

참고로 현재 구현에서는 INDEX_SCAN과 COVERED_INDEX_SCAN의 비용 공식이 동일하다. 실제로는 COVERED_INDEX_SCAN이 테이블 접근을 생략하므로 더 저렴해야 하는데, 이는 향후 개선 과제로 남겨두었다.

---

## 6. 실제 응답 살펴보기

TCP 클라이언트에서 다음 쿼리를 실행한다고 가정하자.

**요청:**
```sql
EXPLAIN SELECT name, email FROM users WHERE email = 'alice@example.com'
```

**응답 (DbResponse.data):**
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
      "description": "Using covering index 'idx_email_name' (selectivity: 0.001%). All columns can be retrieved from index without accessing table data. VERY EFFICIENT"
    }
  ],
  "estimatedCost": 27.4,
  "estimatedRows": 1,
  "isCoveredQuery": true,
  "generatedAt": "2026-02-19T10:00:00Z"
}
```

이 응답에서 읽을 수 있는 정보:

- **COVERED_INDEX_SCAN**: 인덱스만으로 결과를 반환할 수 있다
- **idx_email_name**: email이 leading column인 인덱스를 사용한다
- **estimatedCost: 27.4**: TABLE_SCAN(100,000)에 비해 약 3,700배 저렴하다
- **estimatedRows: 1**: 고유한 email이므로 1행만 반환될 것으로 예상한다
- **isCovered: true**: 테이블 데이터에 접근하지 않는다

---

## 7. 구현하면서 배운 것

### 메타데이터가 전부다

옵티마이저는 실제 데이터를 한 행도 읽지 않는다. System Catalog에 저장된 메타데이터 — 행 수, 카디널리티, 인덱스 정의 — 만으로 실행 계획을 세운다. 그래서 `EXPLAIN`이 빠른 것이고, 그래서 통계가 정확해야 하는 것이다.

MySQL에서 `ANALYZE TABLE`을 실행하라는 조언을 들어본 적이 있을 것이다. 이 명령은 테이블을 스캔해 통계를 갱신한다. 통계가 오래되면 옵티마이저가 잘못된 판단을 내리기 때문이다. 직접 구현해보니 이 말이 실감났다.

### 15% 임계값의 직관

"선택도가 15%를 넘으면 풀스캔이 낫다"는 규칙이 왜 합리적인지, 구현을 통해 이해할 수 있었다.

인덱스를 탈 때는 **Random I/O**가 발생한다. 인덱스에서 위치를 찾고, 디스크의 해당 위치로 점프해 데이터를 읽는다. 반면 테이블 풀스캔은 **Sequential I/O**로, 디스크를 처음부터 끝까지 순서대로 읽는다.

Random I/O는 Sequential I/O보다 느리다(HDD 기준 약 100배, SSD에서도 수 배). 읽어야 할 행이 적으면 Random I/O의 총량이 적으므로 인덱스가 유리하다. 하지만 행이 많아지면 Random I/O가 누적되어 Sequential I/O를 초과하는 지점이 온다. 그 지점이 대략 15% 근방이다.

### Covered Index의 가치

인덱스 설계가 쿼리 성능을 결정한다는 말을 많이 들었지만, Covered Index를 직접 구현해보니 그 의미가 선명해졌다.

일반 INDEX_SCAN은 두 번의 접근이 필요하다:
1. 인덱스에서 행의 위치를 찾고
2. 테이블 데이터에서 나머지 컬럼을 가져온다

COVERED_INDEX_SCAN은 1번만으로 끝난다. 인덱스 자체에 필요한 모든 컬럼이 있기 때문이다. 즉, 인덱스를 설계할 때 **자주 SELECT하는 컬럼까지 포함**시키면 성능이 크게 향상될 수 있다.

### Leading Column Rule

복합 인덱스 `(A, B, C)`에서 WHERE 조건이 B나 C에만 걸려 있으면 이 인덱스를 사용할 수 없다. B-tree에서 데이터는 A → B → C 순서로 정렬되어 있기 때문이다.

전화번호부를 생각하면 된다. 성(A) → 이름(B) → 전화번호(C) 순으로 정렬되어 있으면, 성을 모르고 이름만으로 찾으려면 전체를 뒤져야 한다. 이것이 Leading Column Rule이고, 구현하면서 왜 인덱스 컬럼 순서가 중요한지 납득할 수 있었다.

---

## 8. 한계와 남은 과제

### Critical: DML과 ES 카탈로그의 단절

현재 가장 큰 구조적 문제다.

TCP로 `CREATE TABLE`을 실행하면 `./data/` 디렉토리에 `.dat` 파일은 생성되지만, Elasticsearch에는 아무 변화가 없다. 이후 `EXPLAIN SELECT * FROM 해당테이블`을 실행하면 "Table doesn't exist" 에러가 발생한다.

```
TCP "CREATE TABLE foo (...)"
  → TableFileManager.writeTable()       ← ./data/foo.dat 생성
  → (끝. ES에는 아무것도 안 함)

TCP "EXPLAIN SELECT * FROM foo"
  → TableMetadataService.tableExists("foo") ← ES 조회
  → false → "Table doesn't exist: foo"
```

ES 서비스의 메서드들(`createTable()`, `updateRowCount()`, `initializeStatistics()` 등)은 이미 구현되어 있지만, 호출하는 곳이 없어 데드 코드 상태다. DML 처리 시 ES 서비스를 함께 호출하는 브릿지 코드가 필요하다.

### Critical: ES 장애 시 앱 전체 기동 불가

ES가 다운된 상태에서 Spring Boot를 시작하면, `ElasticsearchRepository` 빈 생성이 실패하여 앱 전체가 기동되지 않는다. EXPLAIN만 안 되는 게 아니라 기본 DB 기능까지 전부 사용할 수 없다. ES 관련 빈을 조건부로 로드하는 설정이 필요하다.

### Medium: 단일 WHERE 조건만 지원

AND/OR 다중 조건 중 첫 번째만 인덱스 선택에 사용된다. 특히 OR 조건은 의미론적으로 잘못 처리된다. `WHERE email = 'a@b.com' OR age > 50`에서 email 인덱스만으로는 `age > 50`인 행을 찾을 수 없는데, 현재 구현은 이를 무시한다.

### Medium: Plan Cache 미활용

`queryHash`로 캐시를 조회하는 메서드가 있지만, `explain()` 내부에서 캐시를 먼저 확인하지 않고 항상 새 계획을 생성한다.

### Medium: CREATE INDEX SQL 미지원

인덱스를 코드나 초기화 스크립트로만 생성할 수 있고, SQL 명령으로는 관리할 수 없다.

### Low: 통계 정확도

매 INSERT마다 `distinctCount`를 무조건 +1 증가시키는 방식이라, 같은 값을 반복 삽입하면 카디널리티가 실제보다 높게 추정된다. HyperLogLog 같은 확률적 자료구조나 주기적 재수집(`ANALYZE TABLE`)이 필요하다.

### Low: 비용 모델의 단순함

현재 비용 모델은 I/O와 CPU 비용을 분리하지 않고, Sequential I/O와 Random I/O의 가중치 차이도 반영하지 않는다. COVERED_INDEX_SCAN의 비용이 일반 INDEX_SCAN과 동일하게 계산되는 것도 개선이 필요하다.

---

## 9. 마무리

완벽한 옵티마이저와는 거리가 멀다. 하지만 "EXPLAIN이 어떻게 동작하는가"를 직접 구현해보는 것만으로도 배움이 컸다.

이제 `EXPLAIN ANALYZE` 결과를 볼 때 각 숫자가 어디서 오는지 감이 온다. 왜 인덱스를 안 타는지, 왜 Covered Index가 효율적인지, 왜 `ANALYZE TABLE`을 실행해야 하는지 — 이론으로 알던 것들이 코드로 확인된 느낌이다.

다음 목표는 DML-ES 연동을 완성하고, 궁극적으로 EXPLAIN이 만든 계획대로 실제 SELECT가 실행되도록 하는 것이다. 그러려면 B-tree 인덱스 파일 구조부터 구현해야 한다. 갈 길이 멀지만, 한 단계씩 쌓아가는 재미가 있다.

---

**구현 우선순위 로드맵:**

| 순서 | 작업 | 근거 |
|------|------|------|
| 1 | DML-ES 카탈로그 연동 | EXPLAIN이 동적 테이블에서 동작하지 않는 근본 원인 |
| 2 | ES 장애 시 Graceful Degradation | ES 없이도 기본 DB 기능이 동작해야 함 |
| 3 | CREATE INDEX / DROP INDEX SQL | 인덱스를 SQL로 관리할 수 없으면 테스트가 불편 |
| 4 | Plan Cache 활용 | 이미 코드가 있으므로 연결만 하면 됨 |
| 5 | OR 조건 처리 | 의미론적 정확성 |
| 6 | QueryLogService 연결 | 모니터링 기반 |
| 7 | 통계 정확도 개선 | 장기적 정확성 |
| 8 | 비용 모델 고도화 | 실무 수준 근접 |
| 9 | EXPLAIN-SELECT 실행 경로 통합 | 최종 목표 |
