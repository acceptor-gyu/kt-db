# 쿼리 처리 파이프라인 (Query Processing Pipeline)

## 개요

SQL 쿼리를 실행 가능한 형태로 변환하는 과정은 프로그래밍 언어의 **컴파일러**와 매우 유사합니다. 이 문서에서는 SQL 문자열이 실제 결과로 변환되는 전체 파이프라인을 설명합니다.

---

## 1. 쿼리 처리 단계 개요

### 전체 파이프라인

```
SQL 문자열
    ↓
┌─────────────────────┐
│  1. 파싱 (Parsing)  │  ← Lexer + Parser
└─────────────────────┘
    ↓ AST (추상 구문 트리)
┌─────────────────────┐
│  2. 의미 분석        │  ← Semantic Analysis
│  (Semantic Analysis)│
└─────────────────────┘
    ↓ Validated Query
┌─────────────────────┐
│  3. 쿼리 최적화      │  ← Query Optimizer
│  (Optimization)     │
└─────────────────────┘
    ↓ Execution Plan
┌─────────────────────┐
│  4. 실행 (Execution)│  ← Execution Engine
└─────────────────────┘
    ↓
실행 결과 (Result Set)
```

### 컴파일러와의 비교

| 단계 | 컴파일러 | 데이터베이스 |
|-----|---------|-------------|
| **입력** | 소스 코드 (.java, .c) | SQL 쿼리 |
| **Lexing** | 토큰화 | SQL 토큰화 |
| **Parsing** | AST 생성 | Query Tree 생성 |
| **Semantic** | 타입 체크, 심볼 테이블 | 테이블/컬럼 존재 검증 |
| **Optimization** | 최적화 패스 | Query Optimizer |
| **Code Gen** | 기계어/바이트코드 | Execution Plan |
| **Execution** | CPU 실행 | Query Executor |

---

## 2. 파싱 (Parsing)

### 핵심 개념

SQL 문자열을 **추상 구문 트리(AST, Abstract Syntax Tree)**로 변환합니다. 이 과정은 다음 두 단계로 구성됩니다:

1. **어휘 분석 (Lexical Analysis)**: 문자열 → 토큰
2. **구문 분석 (Syntax Analysis)**: 토큰 → AST

### 구현 코드

**파일 위치**: `db-server/src/main/kotlin/study/db/server/db_engine/SqlParser.kt`

```kotlin
package study.db.server.db_engine

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select
import net.sf.jsqlparser.statement.insert.Insert
import net.sf.jsqlparser.statement.create.table.CreateTable

class SqlParser {

    /**
     * SQL 문자열을 파싱하여 구조화된 쿼리 객체로 변환
     */
    fun parse(sql: String): ParsedQuery {
        try {
            val statement: Statement = CCJSqlParserUtil.parse(sql)

            return when (statement) {
                is Select -> parseSelect(statement)
                is Insert -> parseInsert(statement)
                is CreateTable -> parseCreateTable(statement)
                else -> throw UnsupportedOperationException(
                    "Unsupported statement type: ${statement.javaClass.simpleName}"
                )
            }
        } catch (e: Exception) {
            throw SqlParseException("Failed to parse SQL: $sql", e)
        }
    }

    private fun parseSelect(select: Select): ParsedQuery {
        val selectBody = select.selectBody as PlainSelect
        val tableName = selectBody.fromItem.toString()
        val columns = selectBody.selectItems.map { it.toString() }
        val whereCondition = selectBody.where?.let { parseWhereClause(it) }
        val orderBy = selectBody.orderByElements?.map { it.toString() }

        return ParsedQuery(
            type = QueryType.SELECT,
            tableName = tableName,
            columns = columns,
            whereCondition = whereCondition,
            orderBy = orderBy
        )
    }

    private fun parseWhereClause(expression: Expression): WhereCondition {
        // WHERE id = 100 AND age > 20
        // → BinaryExpression(left=..., operator=AND, right=...)

        return when (expression) {
            is EqualsTo -> WhereCondition.Equals(
                column = expression.leftExpression.toString(),
                value = expression.rightExpression.toString()
            )
            is GreaterThan -> WhereCondition.GreaterThan(
                column = expression.leftExpression.toString(),
                value = expression.rightExpression.toString()
            )
            is AndExpression -> WhereCondition.And(
                left = parseWhereClause(expression.leftExpression),
                right = parseWhereClause(expression.rightExpression)
            )
            else -> throw UnsupportedOperationException(
                "Unsupported WHERE clause: $expression"
            )
        }
    }
}
```

### JSqlParser 라이브러리

이 프로젝트는 **JSqlParser**를 사용하여 SQL 파싱을 수행합니다.

```kotlin
// 내부적으로 다음과 같이 동작:
// 1. Lexer: "SELECT * FROM users WHERE id = 100"
//    → [SELECT, *, FROM, users, WHERE, id, =, 100]

// 2. Parser: 토큰 스트림을 AST로 변환
SelectStatement
├── SelectItems: [*]
├── FromItem: Table(name="users")
└── Where: EqualsTo
    ├── left: Column(name="id")
    └── right: LongValue(100)
```

### 에러 처리

```kotlin
class SqlParseException(message: String, cause: Throwable?)
    : Exception(message, cause)

// 파싱 오류 예시:
// "SELCT * FROM users" → "Unexpected token: SELCT"
// "SELECT FROM users"  → "Missing select items"
// "SELECT * FROM"      → "Missing table name"
```

---

## 3. 쿼리 표현 (Query Representation)

### ParsedQuery 데이터 클래스

**파일 위치**: `db-server/src/main/kotlin/study/db/server/db_engine/dto/ParsedQuery.kt`

```kotlin
data class ParsedQuery(
    val tableName: String,                           // 대상 테이블명
    val selectColumns: List<String>,                 // SELECT 컬럼 목록
    val whereConditions: List<WhereCondition>,       // WHERE 조건들 (리스트)
    val orderBy: List<String>                        // ORDER BY 컬럼 목록
)
```

**주요 특징**:
- 단순 SELECT 문만 지원 (현재 구현)
- `whereConditions`는 **리스트** 형태로 저장됨 (AND로 연결된 조건들을 평탄화)
- 복잡한 계층 구조 대신 단순한 구조 사용

### WhereCondition 구조

**파일 위치**: `db-server/src/main/kotlin/study/db/server/db_engine/dto/WhereCondition.kt`

```kotlin
data class WhereCondition(
    val column: String,    // 컬럼명
    val operator: String,  // 연산자: "=", ">", "<", ">=", "<=", "!=", "LIKE", etc.
    val value: String      // 비교 값
)
```

**주요 특징**:
- **단순한 data class 구조** (sealed class 계층 구조 없음)
- AND/OR로 연결된 복잡한 조건은 `List<WhereCondition>`로 평탄화
- 각 조건은 독립적으로 저장되며, 실행 시 순차적으로 평가

**예시**:
```kotlin
// SQL: WHERE age > 20 AND status = 'active'
listOf(
    WhereCondition(column = "age", operator = ">", value = "20"),
    WhereCondition(column = "status", operator = "=", value = "'active'")
)
```

### 쿼리 표현 예시

```kotlin
// SQL: SELECT id, name FROM users WHERE age > 20 AND city = 'Seoul'

val query = ParsedQuery(
    tableName = "users",
    selectColumns = listOf("id", "name"),
    whereConditions = listOf(
        WhereCondition(column = "age", operator = ">", value = "20"),
        WhereCondition(column = "city", operator = "=", value = "'Seoul'")
    ),
    orderBy = emptyList()
)
```

**주의**: AND로 연결된 조건들은 리스트로 평탄화되어 저장됩니다.

---

## 4. 의미 분석 (Semantic Analysis)

### 핵심 개념

구문적으로 올바른 쿼리라도 **의미적으로 잘못될 수 있습니다**. 의미 분석 단계에서는 다음을 검증합니다:

1. 테이블이 존재하는가?
2. 컬럼이 존재하는가?
3. 데이터 타입이 올바른가?
4. 별칭(alias)이 올바르게 사용되었는가?

### 구현 코드

**파일 위치**: `db-server/src/main/kotlin/study/db/server/db_engine/Resolver.kt`

```kotlin
class Resolver(
    private val tableService: TableService
) {

    /**
     * 쿼리의 의미적 정확성 검증
     */
    fun resolve(query: ParsedQuery): ResolvedQuery {
        // 1. 테이블 존재 확인
        val table = tableService.getTable(query.tableName)
            ?: throw TableNotFoundException("Table '${query.tableName}' not found")

        // 2. 컬럼 검증
        validateColumns(query.columns, table)

        // 3. WHERE 절 검증
        query.whereCondition?.let { validateWhereClause(it, table) }

        // 4. 데이터 타입 검증
        validateDataTypes(query, table)

        return ResolvedQuery(
            originalQuery = query,
            resolvedTable = table,
            columnMetadata = resolveColumnMetadata(query.columns, table)
        )
    }

    private fun validateColumns(columns: List<String>, table: Table) {
        for (column in columns) {
            if (column == "*") continue  // SELECT * 허용

            if (!table.hasColumn(column)) {
                throw ColumnNotFoundException(
                    "Column '$column' not found in table '${table.name}'"
                )
            }
        }
    }

    private fun validateWhereClause(condition: WhereCondition, table: Table) {
        when (condition) {
            is WhereCondition.Equals -> {
                if (!table.hasColumn(condition.column)) {
                    throw ColumnNotFoundException(
                        "Column '${condition.column}' not found in WHERE clause"
                    )
                }
            }
            is WhereCondition.And -> {
                validateWhereClause(condition.left, table)
                validateWhereClause(condition.right, table)
            }
            // ... 다른 조건 처리
        }
    }

    private fun validateDataTypes(query: ParsedQuery, table: Table) {
        // INSERT VALUES의 타입 검증
        if (query.type == QueryType.INSERT) {
            val columns = table.getColumns()
            for ((index, value) in query.values.withIndex()) {
                val expectedType = columns[index].type
                val actualType = value.javaClass

                if (!isCompatible(expectedType, actualType)) {
                    throw TypeMismatchException(
                        "Expected $expectedType but got $actualType for column ${columns[index].name}"
                    )
                }
            }
        }
    }
}
```

### 에러 예시

```kotlin
// 1. 테이블 없음
// SELECT * FROM nonexistent_table
// → TableNotFoundException: Table 'nonexistent_table' not found

// 2. 컬럼 없음
// SELECT invalid_column FROM users
// → ColumnNotFoundException: Column 'invalid_column' not found in table 'users'

// 3. 타입 불일치
// INSERT INTO users (id, name) VALUES ('not_a_number', 'John')
// → TypeMismatchException: Expected INT but got String for column 'id'
```

---

## 5. 쿼리 최적화 (Query Optimization)

최적화는 별도 문서에서 자세히 다루므로, 여기서는 개요만 설명합니다.

### 최적화 단계

1. **논리적 최적화 (Logical Optimization)**
   - 쿼리 재작성 (Query Rewriting)
   - 불필요한 조건 제거
   - 서브쿼리 전개

2. **물리적 최적화 (Physical Optimization)**
   - 인덱스 선택
   - 조인 순서 결정
   - 실행 계획 생성

### 간단한 최적화 예시

```kotlin
// 원본 쿼리:
// SELECT * FROM users WHERE 1=1 AND age > 20

// 최적화 후:
// SELECT * FROM users WHERE age > 20
// (1=1 조건 제거)

// 원본 쿼리:
// SELECT * FROM users WHERE age > 20 AND id = 100

// 최적화 후: id 인덱스 사용
// INDEX_SEEK(id=100) → FILTER(age>20)
// (id 조건을 먼저 평가하여 스캔 범위 축소)
```

---

## 6. 쿼리 실행 (Query Execution)

### 실행 엔진의 역할

검증되고 최적화된 쿼리를 실제로 실행하여 결과를 반환합니다.

### 실행 모델

#### 1) Volcano Iterator Model (Pull-based)

```kotlin
interface Operator {
    fun open()              // 초기화
    fun next(): Row?        // 다음 행 반환 (없으면 null)
    fun close()             // 정리
}

class TableScanOperator(val table: Table) : Operator {
    private var index = 0

    override fun open() {
        index = 0
    }

    override fun next(): Row? {
        if (index >= table.rowCount()) return null
        return table.getRow(index++)
    }

    override fun close() {
        // 리소스 정리
    }
}

class FilterOperator(
    val child: Operator,
    val condition: WhereCondition
) : Operator {
    override fun next(): Row? {
        while (true) {
            val row = child.next() ?: return null
            if (evaluate(condition, row)) {
                return row  // 조건 만족
            }
            // 조건 불만족 → 다음 행 시도
        }
    }
}

// 실행:
val scan = TableScanOperator(table)
val filter = FilterOperator(scan, condition)

filter.open()
while (true) {
    val row = filter.next() ?: break
    results.add(row)
}
filter.close()
```

**장점**:
- 메모리 효율적 (한 번에 한 행만 처리)
- 파이프라인 병렬화 가능

**단점**:
- 함수 호출 오버헤드
- CPU 캐시 미스

#### 2) Vectorized Execution (Batch-based)

```kotlin
interface VectorizedOperator {
    fun nextBatch(batchSize: Int): List<Row>
}

class VectorizedTableScan(val table: Table) : VectorizedOperator {
    private var offset = 0

    override fun nextBatch(batchSize: Int): List<Row> {
        val batch = table.getRows(offset, batchSize)
        offset += batchSize
        return batch
    }
}

// 한 번에 여러 행을 처리 (SIMD 최적화 가능)
val batch = scan.nextBatch(1000)
val filtered = batch.filter { evaluate(condition, it) }
```

**장점**:
- CPU 캐시 효율 높음
- SIMD 명령어 활용 가능

**단점**:
- 메모리 사용량 증가

---

## 7. 실행 계획 예시

### SELECT 쿼리

```sql
SELECT id, name, age
FROM users
WHERE age > 20 AND city = 'Seoul'
ORDER BY age DESC
LIMIT 10
```

**실행 계획**:
```
LIMIT(10)
  ↓
SORT(age DESC)
  ↓
FILTER(city='Seoul')
  ↓
FILTER(age>20)
  ↓
TABLE_SCAN(users)
```

**실행 순서** (아래에서 위로):
1. `TABLE_SCAN`: users 테이블의 모든 행 읽기
2. `FILTER(age>20)`: age 조건 필터링
3. `FILTER(city='Seoul')`: city 조건 필터링
4. `SORT`: age 기준 내림차순 정렬
5. `LIMIT`: 상위 10개만 반환

### INSERT 쿼리

```sql
INSERT INTO users (id, name, age)
VALUES (101, 'John', 25)
```

**실행 계획**:
```
INSERT
  ↓
VALIDATE(타입 체크)
  ↓
VALUES(101, 'John', 25)
```

---

## 8. 실무 데이터베이스의 쿼리 처리

### MySQL의 쿼리 실행 과정

```
1. Connection Layer: 클라이언트 연결 수락
   ↓
2. Parser: SQL을 parse tree로 변환
   ↓
3. Preprocessor: 테이블/컬럼 존재 확인, 권한 체크
   ↓
4. Optimizer: 실행 계획 생성 (비용 기반)
   ↓
5. Query Executor: 실행 계획 수행
   ↓
6. Storage Engine (InnoDB): 실제 데이터 읽기/쓰기
   ↓
7. Result Set: 결과 반환
```

### PostgreSQL의 EXPLAIN 출력

```sql
EXPLAIN SELECT * FROM users WHERE age > 20;

-- 출력:
Seq Scan on users  (cost=0.00..35.50 rows=10 width=40)
  Filter: (age > 20)
```

**해석**:
- `Seq Scan`: 순차 스캔 (전체 테이블 읽기)
- `cost=0.00..35.50`: 시작 비용 0, 총 비용 35.50
- `rows=10`: 예상 결과 행 수
- `width=40`: 각 행의 평균 바이트 수

---

## 9. 최적화 힌트

### 인덱스 힌트

```sql
-- MySQL
SELECT * FROM users USE INDEX (idx_age) WHERE age > 20;

-- PostgreSQL
SELECT /*+ IndexScan(users idx_age) */ * FROM users WHERE age > 20;
```

### 조인 힌트

```sql
-- MySQL
SELECT /*+ JOIN_ORDER(a, b, c) */ *
FROM a
JOIN b ON a.id = b.a_id
JOIN c ON b.id = c.b_id;
```

---

## 10. 디버깅 팁

### 쿼리 파싱 테스트

```kotlin
@Test
fun testSqlParsing() {
    val parser = SqlParser()

    val sql = "SELECT id, name FROM users WHERE age > 20"
    val query = parser.parse(sql)

    assertEquals(QueryType.SELECT, query.type)
    assertEquals("users", query.tableName)
    assertEquals(listOf("id", "name"), query.columns)
    assertNotNull(query.whereCondition)
}
```

### AST 시각화

```kotlin
fun printAST(condition: WhereCondition, indent: Int = 0) {
    val prefix = "  ".repeat(indent)

    when (condition) {
        is WhereCondition.Equals -> {
            println("$prefix EQUALS")
            println("$prefix   column: ${condition.column}")
            println("$prefix   value: ${condition.value}")
        }
        is WhereCondition.And -> {
            println("$prefix AND")
            printAST(condition.left, indent + 1)
            printAST(condition.right, indent + 1)
        }
        // ... 다른 조건
    }
}

// 출력:
// AND
//   GREATER_THAN
//     column: age
//     value: 20
//   EQUALS
//     column: city
//     value: Seoul
```

---

## 11. 학습 포인트 요약

| 단계 | 입력 | 출력 | 컴파일러 비유 |
|-----|-----|------|-------------|
| **Parsing** | SQL 문자열 | AST | 구문 분석 |
| **Semantic** | AST | Validated Query | 의미 분석 |
| **Optimization** | Query | Execution Plan | 최적화 패스 |
| **Execution** | Plan | Result Set | 코드 실행 |

---

## 참고 자료

- [JSqlParser Documentation](https://github.com/JSQLParser/JSqlParser)
- [MySQL Query Execution](https://dev.mysql.com/doc/refman/8.0/en/select-optimization.html)
- [PostgreSQL Query Planning](https://www.postgresql.org/docs/current/planner-optimizer.html)
- [Compilers: Principles, Techniques, and Tools (Dragon Book)](https://en.wikipedia.org/wiki/Compilers:_Principles,_Techniques,_and_Tools)
- [Database System Concepts (Silberschatz)](https://www.db-book.com/)
