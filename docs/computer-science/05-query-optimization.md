# 쿼리 최적화 (Query Optimization)

## 개요

같은 결과를 반환하는 쿼리라도 **어떻게 실행하느냐**에 따라 성능이 수천 배 차이날 수 있습니다. 쿼리 최적화는 데이터베이스의 가장 복잡하면서도 중요한 부분으로, 이 문서에서는 비용 기반 최적화(Cost-Based Optimization)와 실행 계획(Execution Plan) 생성에 대해 설명합니다.

---

## 1. 쿼리 최적화의 중요성

### 성능 차이 예시

```sql
-- 쿼리: 100만 행 중 id=100인 행 찾기

-- 방법 1: 전체 테이블 스캔 (Full Table Scan)
-- 읽어야 할 행: 1,000,000 행
-- 예상 시간: 10초

-- 방법 2: 인덱스 검색 (Index Seek)
-- 읽어야 할 행: 1 행 (+ 인덱스 탐색 3-4회)
-- 예상 시간: 0.001초

-- 성능 차이: 10,000배!
```

### 최적화가 필요한 이유

1. **사용자는 결과만 명시** (What)
   - SQL은 선언적 언어 (Declarative)
   - "어떻게" 실행할지는 DBMS가 결정

2. **다양한 실행 방법 존재**
   - 같은 결과를 얻는 방법이 수십 가지
   - 최적의 방법을 자동으로 선택

3. **데이터 특성에 따라 최적 방법 변화**
   - 데이터 크기, 분포, 인덱스 유무 등

---

## 2. 최적화 단계

### 전체 프로세스

```
ParsedQuery
    ↓
┌──────────────────────────┐
│ 1. 논리적 최적화          │  ← 쿼리 재작성
│ (Logical Optimization)  │
└──────────────────────────┘
    ↓ Optimized Query
┌──────────────────────────┐
│ 2. 통계 수집              │  ← Table Statistics
│ (Statistics Gathering)  │
└──────────────────────────┘
    ↓ Statistics
┌──────────────────────────┐
│ 3. 비용 계산              │  ← Cost Estimation
│ (Cost Estimation)       │
└──────────────────────────┘
    ↓ Execution Plans
┌──────────────────────────┐
│ 4. 최적 계획 선택         │  ← Plan Selection
│ (Plan Selection)        │
└──────────────────────────┘
    ↓ Best Plan
실행 (Execution)
```

---

## 3. EXPLAIN 기능 구현

### 핵심 개념

`EXPLAIN` 명령은 쿼리가 어떻게 실행될지 미리 보여줍니다. 성능 튜닝의 핵심 도구입니다.

### 구현 코드

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/service/ExplainService.kt`

```kotlin
@Service
class ExplainService(
    private val sqlParser: SqlParser,
    private val tableMetadataService: TableMetadataService,
    private val indexMetadataService: IndexMetadataService,
    private val tableStatisticsService: TableStatisticsService,
    private val queryPlanRepository: QueryPlanRepository
) {

    /**
     * EXPLAIN 실행
     */
    fun explain(sql: String): QueryPlan {
        // 1. SQL 파싱
        val parsedQuery = sqlParser.parseQuery(sql)

        // 2. 테이블 존재 확인
        val tableName = parsedQuery.tableName
        if (!tableMetadataService.tableExists(tableName)) {
            throw IllegalStateException("Table doesn't exist: $tableName")
        }

        // 3. WHERE 절 분석 (첫 번째 조건만 사용)
        val firstCondition = parsedQuery.whereConditions.firstOrNull()
        val whereColumn = firstCondition?.column

        // 4. SELECT 절 컬럼 추출
        val selectColumns = if (parsedQuery.selectColumns.contains("*")) {
            tableMetadataService.getTableColumns(tableName)?.map { it.name } ?: emptyList()
        } else {
            parsedQuery.selectColumns
        }

        // 5. 실행 계획 생성
        val executionSteps = generateExecutionPlan(tableName, whereColumn, selectColumns)

        // 6. QueryPlan 생성 및 저장
        val queryPlan = QueryPlan(
            planId = UUID.randomUUID().toString(),
            queryText = sql,
            queryHash = generateQueryHash(sql),
            executionSteps = executionSteps,
            estimatedCost = executionSteps.sumOf { it.estimatedCost },
            estimatedRows = executionSteps.lastOrNull()?.estimatedRows ?: 0,
            isCoveredQuery = executionSteps.any { it.isCovered }
        )

        return queryPlanRepository.save(queryPlan)
    }

    private fun generateExecutionPlan(
        query: ParsedQuery,
        tableMetadata: TableMetadata,
        indexes: List<IndexMetadata>,
        statistics: TableStatistics?
    ): QueryPlan {
        val steps = mutableListOf<ExecutionStep>()

        // Step 1: 테이블 접근 방법 결정
        val accessStep = chooseAccessMethod(query, indexes, statistics)
        steps.add(accessStep)

        // Step 2: WHERE 조건 필터링
        query.whereCondition?.let {
            steps.add(ExecutionStep(
                type = StepType.FILTER,
                description = "Apply WHERE condition: $it",
                estimatedRows = estimateFilteredRows(statistics, it),
                estimatedCost = calculateFilterCost(statistics, it)
            ))
        }

        // Step 3: ORDER BY 정렬
        query.orderBy?.let {
            steps.add(ExecutionStep(
                type = StepType.SORT,
                description = "Sort by: ${it.joinToString(", ")}",
                estimatedRows = steps.last().estimatedRows,
                estimatedCost = calculateSortCost(steps.last().estimatedRows)
            ))
        }

        // Step 4: LIMIT 적용
        query.limit?.let {
            steps.add(ExecutionStep(
                type = StepType.LIMIT,
                description = "Limit to $it rows",
                estimatedRows = minOf(it, steps.last().estimatedRows),
                estimatedCost = 0.0  // LIMIT은 비용 추가 없음
            ))
        }

        return QueryPlan(
            sql = query.toString(),
            steps = steps,
            totalEstimatedCost = steps.sumOf { it.estimatedCost },
            totalEstimatedRows = steps.lastOrNull()?.estimatedRows ?: 0
        )
    }

    /**
     * 실행 계획 생성
     */
    private fun generateExecutionPlan(
        tableName: String,
        whereColumn: String?,
        selectColumns: List<String>
    ): List<ExecutionStep> {
        val steps = mutableListOf<ExecutionStep>()

        // 테이블 통계 조회
        val stats = tableStatisticsService.getStatistics(tableName)
        val totalRows = stats?.totalRows ?: 0

        if (whereColumn != null) {
            // WHERE 절이 있는 경우
            val selectivity = tableStatisticsService.calculateSelectivity(tableName, whereColumn)
            val decision = indexMetadataService.shouldUseIndexScan(
                tableName, whereColumn, selectivity
            )

            if (decision.useIndex && decision.selectedIndex != null) {
                // INDEX_SCAN 또는 COVERED_INDEX_SCAN
                val isCovered = isCoveredQuery(
                    decision.selectedIndex.indexColumns,
                    selectColumns,
                    whereColumn
                )
                val stepType = if (isCovered) "COVERED_INDEX_SCAN" else "INDEX_SCAN"

                steps.add(
                    ExecutionStep(
                        stepId = 1,
                        stepType = stepType,
                        tableName = tableName,
                        indexUsed = decision.selectedIndex.indexName,
                        filterCondition = "$whereColumn = ...",
                        columnsAccessed = selectColumns,
                        estimatedCost = calculateIndexScanCost(totalRows, selectivity),
                        estimatedRows = (totalRows * selectivity).toLong(),
                        isCovered = isCovered,
                        description = "Using index '${decision.selectedIndex.indexName}'"
                    )
                )
            } else {
                // TABLE_SCAN
                steps.add(
                    ExecutionStep(
                        stepId = 1,
                        stepType = "TABLE_SCAN",
                        tableName = tableName,
                        indexUsed = null,
                        filterCondition = "$whereColumn = ...",
                        columnsAccessed = selectColumns,
                        estimatedCost = totalRows.toDouble(),
                        estimatedRows = (totalRows * selectivity).toLong(),
                        isCovered = false,
                        description = "Full table scan. ${decision.reason}"
                    )
                )
            }
        } else {
            // WHERE 절이 없는 경우
            steps.add(
                ExecutionStep(
                    stepId = 1,
                    stepType = "TABLE_SCAN",
                    tableName = tableName,
                    indexUsed = null,
                    filterCondition = null,
                    columnsAccessed = selectColumns,
                    estimatedCost = totalRows.toDouble(),
                    estimatedRows = totalRows,
                    isCovered = false,
                    description = "Full table scan (no WHERE clause)"
                )
            )
        }

        return steps
    }

    /**
     * 최적 인덱스 선택
     */
    private fun findBestIndex(
        whereCondition: WhereCondition,
        indexes: List<IndexMetadata>,
        statistics: TableStatistics?
    ): IndexMetadata? {
        // WHERE 조건에 사용된 컬럼 추출
        val usedColumns = extractUsedColumns(whereCondition)

        // 사용 가능한 인덱스 필터링
        val candidates = indexes.filter { index ->
            // 인덱스의 첫 번째 컬럼이 WHERE 조건에 포함되어야 함
            index.columns.first() in usedColumns
        }

        if (candidates.isEmpty()) return null

        // 선택도가 가장 높은 인덱스 선택 (distinctCount가 많을수록 좋음)
        return candidates.maxByOrNull { index ->
            val column = index.columns.first()
            statistics?.columnStatistics?.get(column)?.distinctCount ?: 0
        }
    }

    /**
     * 필터링 후 행 수 추정
     */
    private fun estimateFilteredRows(
        statistics: TableStatistics?,
        condition: WhereCondition
    ): Long {
        if (statistics == null) return 0

        return when (condition) {
            is WhereCondition.Equals -> {
                // Selectivity = 1 / distinctCount
                val column = condition.column
                val distinctCount = statistics.columnStatistics[column]?.distinctCount ?: 1
                statistics.rowCount / distinctCount
            }
            is WhereCondition.GreaterThan -> {
                // 대략 50% 추정 (정확한 히스토그램 없이)
                statistics.rowCount / 2
            }
            is WhereCondition.And -> {
                // AND: 두 조건의 선택도를 곱함
                val left = estimateFilteredRows(statistics, condition.left)
                val right = estimateFilteredRows(statistics, condition.right)
                (left * right) / statistics.rowCount
            }
            else -> statistics.rowCount
        }
    }

    /**
     * 비용 계산
     */
    private fun calculateTableScanCost(statistics: TableStatistics?): Double {
        // 전체 테이블 스캔 비용 = 행 개수 * 페이지 읽기 비용
        val rowCount = statistics?.rowCount ?: 0
        val pageCost = 1.0        // 페이지 읽기 비용
        val cpuCost = 0.01        // CPU 비용 (행당)

        return (rowCount * cpuCost) + (rowCount / 100.0 * pageCost)
    }

    private fun calculateIndexCost(estimatedRows: Long, isCovered: Boolean): Double {
        val indexSeekCost = 3.0   // 인덱스 탐색 비용 (B-tree 깊이)
        val rowFetchCost = if (isCovered) 0.0 else 1.0  // 테이블 행 가져오기 비용

        return indexSeekCost + (estimatedRows * rowFetchCost * 0.01)
    }

    private fun calculateSortCost(rows: Long): Double {
        // 정렬 비용 = O(n log n)
        if (rows == 0L) return 0.0
        return rows * Math.log(rows.toDouble()) * 0.001
    }
}
```

### 실행 계획 데이터 구조

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/document/QueryPlan.kt`

```kotlin
@Document(indexName = "query_plans")
data class QueryPlan(
    @Id
    val planId: String,              // UUID
    val queryText: String,           // 원본 SQL
    val queryHash: String,           // MD5 해시 (캐싱용)
    val executionSteps: List<ExecutionStep>,
    val estimatedCost: Double,
    val estimatedRows: Long,
    val isCoveredQuery: Boolean = false,
    val generatedAt: Instant = Instant.now()
)

data class ExecutionStep(
    val stepId: Int,                 // 실행 순서
    val stepType: String,            // "TABLE_SCAN", "INDEX_SCAN", "COVERED_INDEX_SCAN"
    val tableName: String?,
    val indexUsed: String?,          // 사용된 인덱스 이름
    val filterCondition: String?,    // WHERE 조건
    val columnsAccessed: List<String> = emptyList(),
    val estimatedCost: Double,
    val estimatedRows: Long,
    val isCovered: Boolean = false,
    val description: String,
    val extra: Map<String, Any> = emptyMap()
)

// stepType 가능한 값
enum class ExplainStepType {
    TABLE_SCAN,
    INDEX_SCAN,
    INDEX_SEEK,
    COVERED_INDEX_SCAN,
    FILTER,
    SORT,
    LIMIT
}
```

**주의**: `stepType`은 String으로 저장되지만, `ExplainStepType` enum을 참고하여 사용합니다.

---

## 4. 비용 기반 최적화 (Cost-Based Optimization)

### 핵심 개념

각 실행 방법의 **예상 비용**을 계산하여 가장 저렴한 방법을 선택합니다.

### 비용 모델

```kotlin
총 비용 = I/O 비용 + CPU 비용 + 메모리 비용

// I/O 비용 (가장 큼)
val ioCost = (읽을 페이지 수) * PAGE_READ_COST

// CPU 비용
val cpuCost = (처리할 행 수) * ROW_PROCESS_COST

// 메모리 비용 (정렬, 해시 조인 등)
val memoryCost = (사용할 메모리) * MEMORY_COST
```

### 예시: 전체 스캔 vs 인덱스

```sql
-- 테이블: users (1,000,000 rows)
-- 인덱스: idx_age on age
-- 쿼리: SELECT * FROM users WHERE age = 25

-- 방법 1: 전체 테이블 스캔
읽을 행: 1,000,000
I/O 비용: 1,000,000 / 100 * 1.0 = 10,000 (페이지당 100행 가정)
CPU 비용: 1,000,000 * 0.01 = 10,000
총 비용: 20,000

-- 방법 2: 인덱스 사용
인덱스 탐색: 3 (B-tree 깊이)
age=25인 행: 1,000 (distinctCount=1000 가정)
I/O 비용: 3 + (1,000 / 100) = 13
CPU 비용: 1,000 * 0.01 = 10
총 비용: 23

결론: 인덱스 사용이 약 1000배 빠름!
```

---

## 5. 선택도 (Selectivity) 계산

### 핵심 개념

**선택도**는 조건을 만족하는 행의 비율입니다. 옵티마이저가 행 수를 추정하는 핵심 지표입니다.

### 계산 공식

```kotlin
// 1. Equality (=) 조건
// WHERE id = 100
selectivity = 1 / distinctCount(id)

// 예: distinctCount(id) = 1,000
// selectivity = 1 / 1,000 = 0.001 (0.1%)

// 2. Range (<, >, BETWEEN) 조건
// WHERE age > 20
selectivity = (maxValue - filterValue) / (maxValue - minValue)

// 예: min=0, max=100, filter=20
// selectivity = (100 - 20) / (100 - 0) = 0.8 (80%)

// 3. AND 조건
// WHERE age > 20 AND city = 'Seoul'
selectivity = selectivity(age > 20) * selectivity(city = 'Seoul')

// 예: 0.8 * 0.1 = 0.08 (8%)

// 4. OR 조건
// WHERE age > 20 OR city = 'Seoul'
selectivity = s1 + s2 - (s1 * s2)

// 예: 0.8 + 0.1 - (0.8 * 0.1) = 0.82 (82%)
```

### 예상 행 수 계산

```kotlin
estimatedRows = totalRows * selectivity

// 예: 테이블 1,000,000 행, selectivity = 0.001
// estimatedRows = 1,000,000 * 0.001 = 1,000 행
```

---

## 6. 인덱스 선택 전략

### 인덱스 사용 가능 조건

```sql
-- ✅ 인덱스 사용 가능
WHERE indexed_column = value
WHERE indexed_column > value
WHERE indexed_column BETWEEN v1 AND v2
WHERE indexed_column IN (v1, v2, v3)

-- ❌ 인덱스 사용 불가
WHERE non_indexed_column = value
WHERE UPPER(indexed_column) = value    -- 함수 적용
WHERE indexed_column + 10 = value      -- 연산 적용
WHERE indexed_column LIKE '%pattern'   -- 와일드카드 앞에
```

### 복합 인덱스 (Composite Index)

```sql
-- 인덱스: idx_name_age on (name, age)

-- ✅ 인덱스 사용
WHERE name = 'John'                    -- 첫 번째 컬럼만
WHERE name = 'John' AND age > 20       -- 두 컬럼 모두

-- ❌ 인덱스 사용 불가
WHERE age > 20                         -- 첫 번째 컬럼 없음
```

**규칙**: 인덱스는 왼쪽부터 순서대로만 사용 가능 (Leftmost Prefix Rule)

### 커버드 쿼리 (Covered Query)

```sql
-- 인덱스: idx_name_age on (name, age)

-- ✅ Covered Query (테이블 접근 불필요)
SELECT name, age FROM users WHERE name = 'John'

-- ❌ Non-Covered Query (테이블 접근 필요)
SELECT name, age, email FROM users WHERE name = 'John'
-- email은 인덱스에 없음 → 테이블에서 가져와야 함
```

**장점**: 인덱스만으로 쿼리 완료 가능 → 2-3배 빠름

---

## 7. 쿼리 재작성 (Query Rewriting)

### 불필요한 조건 제거

```sql
-- Before
SELECT * FROM users WHERE 1=1 AND age > 20

-- After
SELECT * FROM users WHERE age > 20
```

### 서브쿼리 플랫화 (Subquery Flattening)

```sql
-- Before (Nested Loop)
SELECT * FROM orders
WHERE customer_id IN (
    SELECT id FROM customers WHERE city = 'Seoul'
)

-- After (Join)
SELECT o.* FROM orders o
INNER JOIN customers c ON o.customer_id = c.id
WHERE c.city = 'Seoul'
```

### 조건 순서 최적화

```sql
-- Before (비효율적)
WHERE expensive_function(column) AND indexed_column = 100

-- After (효율적)
WHERE indexed_column = 100 AND expensive_function(column)
-- 인덱스로 먼저 필터링 → 비싼 함수는 적은 행에만 적용
```

---

## 8. 실제 EXPLAIN 출력 예시

### MySQL EXPLAIN

```sql
EXPLAIN SELECT * FROM users WHERE age > 20;

+----+-------------+-------+------+---------------+------+---------+------+------+-------------+
| id | select_type | table | type | possible_keys | key  | key_len | ref  | rows | Extra       |
+----+-------------+-------+------+---------------+------+---------+------+------+-------------+
|  1 | SIMPLE      | users | ALL  | NULL          | NULL | NULL    | NULL | 1000 | Using where |
+----+-------------+-------+------+---------------+------+---------+------+------+-------------+
```

**해석**:
- `type: ALL` → 전체 테이블 스캔 (느림)
- `key: NULL` → 인덱스 미사용
- `rows: 1000` → 1000행 검사 예상

### PostgreSQL EXPLAIN ANALYZE

```sql
EXPLAIN ANALYZE SELECT * FROM users WHERE age > 20;

Seq Scan on users  (cost=0.00..18.50 rows=5 width=40) (actual time=0.012..0.034 rows=5 loops=1)
  Filter: (age > 20)
  Rows Removed by Filter: 3
Planning Time: 0.082 ms
Execution Time: 0.051 ms
```

**해석**:
- `Seq Scan` → 순차 스캔
- `cost=0.00..18.50` → 예상 비용
- `rows=5` → 예상 행 수
- `actual time=0.012..0.034` → 실제 시간
- `Rows Removed by Filter: 3` → 필터링된 행 수

---

## 9. 최적화 실습 예제

### 예제 1: 인덱스 추가로 성능 개선

```kotlin
@Test
fun testOptimizationWithIndex() {
    // 1. 인덱스 없이 쿼리 실행
    val plan1 = explainService.explain(
        "SELECT * FROM users WHERE age > 20"
    )
    println("Without index: cost=${plan1.totalEstimatedCost}")
    // 출력: Without index: cost=20000.0

    // 2. 인덱스 생성
    indexService.createIndex("idx_age", "users", listOf("age"))

    // 3. 인덱스와 함께 쿼리 실행
    val plan2 = explainService.explain(
        "SELECT * FROM users WHERE age > 20"
    )
    println("With index: cost=${plan2.totalEstimatedCost}")
    // 출력: With index: cost=23.0

    // 성능 향상: 약 1000배
}
```

### 예제 2: Covered Query

```kotlin
@Test
fun testCoveredQuery() {
    // 인덱스: (name, age)
    indexService.createIndex("idx_name_age", "users", listOf("name", "age"))

    // Covered Query
    val plan1 = explainService.explain(
        "SELECT name, age FROM users WHERE name = 'John'"
    )
    assertEquals(StepType.COVERED_INDEX_SCAN, plan1.steps[0].type)

    // Non-Covered Query (email은 인덱스에 없음)
    val plan2 = explainService.explain(
        "SELECT name, age, email FROM users WHERE name = 'John'"
    )
    assertEquals(StepType.INDEX_SEEK, plan2.steps[0].type)
}
```

---

## 10. 학습 포인트 요약

| 개념 | 핵심 내용 | 실무 활용 |
|-----|---------|----------|
| **Cost Model** | I/O + CPU + 메모리 비용 | 실행 계획 비교 |
| **Selectivity** | 조건 만족 행 비율 | 행 수 추정 |
| **Index Selection** | 선택도 기반 인덱스 선택 | 쿼리 튜닝 |
| **Covered Query** | 인덱스만으로 쿼리 완료 | 성능 2-3배 향상 |
| **EXPLAIN** | 실행 계획 확인 | 성능 분석 필수 도구 |

---

## 참고 자료

- [MySQL EXPLAIN 가이드](https://dev.mysql.com/doc/refman/8.0/en/explain-output.html)
- [PostgreSQL Query Planning](https://www.postgresql.org/docs/current/planner-optimizer.html)
- [Use The Index, Luke!](https://use-the-index-luke.com/) - 인덱스 최적화 가이드
- [Database System Concepts - Query Optimization](https://www.db-book.com/)
