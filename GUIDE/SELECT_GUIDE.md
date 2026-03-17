# SELECT 강화 구현 가이드

## 개요

이 문서는 `feat/advance-select` 브랜치에서 구현된 SELECT 쿼리 강화 기능을 설명합니다.
기존에는 `SELECT * FROM table` 형태의 전체 풀스캔만 지원했으나, 이번 변경으로 실제 RDBMS와 유사한 수준의 SELECT 문을 지원합니다.

### 지원하는 SELECT 문법

```sql
SELECT * FROM users
SELECT name, age FROM users
SELECT * FROM users WHERE age > 20
SELECT name FROM users WHERE age > 20 ORDER BY name ASC
SELECT * FROM users ORDER BY age DESC, name ASC
SELECT * FROM users LIMIT 10
SELECT * FROM users LIMIT 10 OFFSET 20
SELECT name, price FROM products WHERE category='fruit' ORDER BY price DESC LIMIT 5 OFFSET 0
```

---

## 실행 파이프라인

SQL 표준 실행 순서를 따릅니다:

```
FROM (전체 테이블 읽기)
  → WHERE 필터링
  → ORDER BY 정렬
  → OFFSET / LIMIT 페이지네이션
  → 컬럼 프로젝션 (SELECT 절)
```

프로젝션이 마지막인 이유: `SELECT name FROM users WHERE age > 20 ORDER BY age`처럼
SELECT 절에 없는 컬럼(age)이 WHERE / ORDER BY에 필요할 수 있기 때문입니다.

---

## 구현 내용

### 1. WHERE 조건 필터링

**변경 파일:** `TableService.kt`, `SqlParser.kt`, `ParsedQuery.kt`

기존 DELETE에서 이미 동작 중이던 `WhereClause` / `WhereEvaluator`를 SELECT에서도 재사용합니다.

```kotlin
// TableService.select()
val filteredRows = if (whereString == null) {
    table.rows
} else {
    val whereClause = WhereClause.parse(whereString)
    validateWhereColumns(table, whereClause)   // 컬럼 존재 여부 검증
    table.rows.filter { row ->
        WhereEvaluator.matches(Row(data = row), whereClause, table.dataType)
    }
}
```

**지원 연산자:** `=`, `!=`, `>`, `<`, `>=`, `<=`, `AND`, `OR`

**타입별 비교:**
- `INT` 컬럼: 숫자 순서로 비교 (`"3" < "25" < "100"`)
- `VARCHAR` 컬럼: 사전순(lexicographic) 비교

**SqlParser에서 WHERE 원문 보존:**
```kotlin
// ParsedQuery에 whereString 필드 추가
whereString = plainSelect.where?.toString()
```

JSqlParser의 `WhereCondition` 타입과 기존 `WhereClause` 타입이 달라
원문 문자열을 그대로 추출해 `WhereClause.parse()`에 전달하는 브릿지 방식을 사용합니다.

---

### 2. 특정 컬럼 선택 (프로젝션)

**변경 파일:** `TableService.kt`, `Resolver.kt`

```kotlin
// TableService.select()
if (columns == null || columns == listOf("*")) {
    return table.copy(rows = pagedRows)
}

Resolver.validateSelectColumns(table, columns)  // 컬럼 존재 여부 검증

val projectedRows = pagedRows.map { row ->
    row.filterKeys { it in columns }
}
val projectedDataType = table.dataType.filterKeys { it in columns }

return table.copy(rows = projectedRows, dataType = projectedDataType)
```

`dataType` 맵도 함께 프로젝션되어 반환된 `Table` 객체가 항상 일관된 상태를 유지합니다.

**Resolver에 추가된 검증 메서드:**
```kotlin
fun validateSelectColumns(table: Table, columns: List<String>) {
    if (columns.contains("*")) return
    columns.forEach { columnName -> validateColumnExists(table, columnName) }
}
```

---

### 3. ORDER BY 정렬

**변경 파일:** `TableService.kt`, `SqlParser.kt`, `OrderByColumn.kt`(신규), `ParsedQuery.kt`, `Resolver.kt`

#### OrderByColumn DTO

```kotlin
data class OrderByColumn(
    val columnName: String,
    val ascending: Boolean = true   // true=ASC, false=DESC
)
```

#### SqlParser에서 ASC/DESC 파싱

```kotlin
private fun parseOrderByColumns(plainSelect: PlainSelect): List<OrderByColumn> {
    val orderByElements = plainSelect.orderByElements ?: return emptyList()
    return orderByElements.map { element ->
        OrderByColumn(
            columnName = element.expression.toString(),
            ascending = element.isAsc
        )
    }
}
```

#### 타입 인식 정렬

ORDER BY도 WHERE와 동일하게 컬럼 타입을 인식하여 비교합니다.
`common` 모듈의 `WhereEvaluator.compareValues()`는 `internal` 접근 제한으로 `db-server` 모듈에서 직접 호출할 수 없어,
`TableService` 내부에 `compareByType()`을 별도 구현합니다:

```kotlin
private fun compareByType(left: String, right: String, columnType: String): Int {
    return when (columnType) {
        "INT"       -> (left.toIntOrNull() ?: 0).compareTo(right.toIntOrNull() ?: 0)
        "TIMESTAMP" -> (left.toLongOrNull() ?: 0L).compareTo(right.toLongOrNull() ?: 0L)
        else        -> left.compareTo(right)   // VARCHAR: 사전순
    }
}
```

다중 컬럼 정렬은 첫 번째 컬럼이 같을 때 다음 컬럼으로 넘어가는 Comparator로 구현합니다:

```kotlin
filteredRows.sortedWith(
    Comparator { row1, row2 ->
        for (col in orderBy) {
            val cmp = compareByType(row1[col.columnName] ?: "", row2[col.columnName] ?: "", ...)
            if (cmp != 0) return@Comparator if (col.ascending) cmp else -cmp
        }
        0
    }
)
```

---

### 4. LIMIT / OFFSET 페이지네이션

**변경 파일:** `TableService.kt`, `SqlParser.kt`, `ParsedQuery.kt`

```kotlin
// OFFSET/LIMIT 적용 (정렬 후, 프로젝션 전)
val pagedRows = sortedRows
    .let { if (offset != null) it.drop(offset) else it }
    .let { if (limit != null) it.take(limit) else it }
```

Kotlin의 `drop()` / `take()`를 사용합니다:
- `offset`이 전체 행 수보다 크면 `drop()`이 빈 리스트 반환 → 정상 처리
- `limit`이 남은 행 수보다 크면 `take()`가 전부 반환 → 정상 처리
- `limit = 0` → 빈 리스트 반환

**SqlParser에서 LIMIT/OFFSET 파싱:**

JSqlParser의 `Limit` 객체에서 `rowCount`와 `offset`이 `Expression` 타입이므로
`.toString().toIntOrNull()`로 변환합니다:

```kotlin
limit  = plainSelect.limit?.rowCount?.toString()?.toIntOrNull()
offset = plainSelect.offset?.offset?.toString()?.toIntOrNull()
```

> 주의: `LIMIT 10 OFFSET 5` 문법에서 offset은 `plainSelect.limit.offset`이 아닌
> `plainSelect.offset.offset`에 저장됩니다.

---

### 5. ConnectionHandler SELECT 처리 전환

**변경 파일:** `ConnectionHandler.kt`, `DbTcpServer.kt`

기존 regex 기반 파싱에서 `SqlParser` 기반으로 전환하여 전체 파이프라인을 TCP 레이어에 연결했습니다.

**이전:**
```kotlin
// regex로 테이블명만 추출, WHERE/ORDER BY/LIMIT 무시
val tableName = match.groupValues[1]
val table = tableService.select(tableName)
```

**이후:**
```kotlin
val parsed = sqlParser.parseQuery(sql)

val columns = parsed.selectColumns.takeUnless { it == listOf("*") }
val orderBy = parsed.orderByColumns.ifEmpty { null }

val table = tableService.select(
    tableName  = parsed.tableName,
    whereString = parsed.whereString,
    columns    = columns,
    orderBy    = orderBy,
    limit      = parsed.limit,
    offset     = parsed.offset
)
```

`ConnectionHandler` 생성자에 `sqlParser: SqlParser = SqlParser()` 기본값을 주어
기존 테스트 코드 변경 없이 하위 호환을 유지합니다.

---

## 변경 파일 요약

| 파일 | 변경 내용 |
|------|-----------|
| `common/.../where/WhereEvaluator.kt` | `compareValues()` `private` → `internal` |
| `db-server/.../dto/OrderByColumn.kt` | 신규 생성: `columnName`, `ascending` 필드 |
| `db-server/.../dto/ParsedQuery.kt` | `whereString`, `orderByColumns`, `limit`, `offset` 필드 추가 |
| `db-server/.../SqlParser.kt` | `parseOrderByColumns()` 추가, `parseQuery()` 신규 필드 채움, OFFSET 파싱 수정 |
| `db-server/.../Resolver.kt` | `validateSelectColumns()`, `validateOrderByColumns()` 추가 |
| `db-server/.../service/TableService.kt` | `select()` 파이프라인 전면 구현 (WHERE→ORDER BY→LIMIT/OFFSET→프로젝션) |
| `db-server/.../ConnectionHandler.kt` | `parseAndHandleSelect()` regex→SqlParser 전환, `sqlParser` 생성자 파라미터 추가 |
| `db-server/DbTcpServer.kt` | `ConnectionHandler` 생성 시 `SqlParser()` 전달 |

---

## 테스트

```bash
# SELECT 관련 테스트 전체 실행
./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"
./gradlew :db-server:test --tests "study.db.server.db_engine.SqlParserTest"

# 전체 테스트
./gradlew test
```

### 추가된 테스트 클래스

| 테스트 클래스 | 테스트 수 | 검증 내용 |
|--------------|----------|-----------|
| `SelectWhereTest` | 8개 | 등호/비교/AND/OR, INT 숫자 비교, 빈 결과, 예외 |
| `SelectColumnProjectionTest` | 5개 | 특정 컬럼, SELECT *, 존재하지 않는 컬럼, WHERE+프로젝션, dataType 프로젝션 |
| `SelectOrderByTest` | 8개 | ASC/DESC, INT 숫자순, 다중 컬럼, WHERE+ORDER BY, 예외 |
| `SelectLimitOffsetTest` | 8개 | LIMIT/OFFSET 단독·조합, 경계값, ORDER BY+LIMIT, 풀 파이프라인 |
| `SelectFullPipelineTest` | 4개 | WHERE+컬럼+ORDER BY+LIMIT/OFFSET 조합 통합 테스트 |

---

## 설계 결정 사항

### ParsedQuery 필드 확장 전략
`ExplainService`가 기존 `orderBy: List<String>` 필드를 6곳 이상 참조하므로 기존 필드를 삭제하지 않고
신규 필드(`orderByColumns`, `whereString`, `limit`, `offset`)를 기본값과 함께 추가했습니다.
기존 4개 인자 생성자 호출은 변경 없이 동작합니다.

### 모듈 경계와 compareByType
`WhereEvaluator.compareValues()`를 `internal`로 변경했지만, Kotlin `internal`은 같은 Gradle 모듈 내에서만 접근 가능합니다.
`WhereEvaluator`는 `common` 모듈, ORDER BY 정렬은 `db-server` 모듈이므로 직접 호출이 불가합니다.
따라서 `TableService` 내부에 `compareByType()`을 별도 구현했습니다.
