# Phase 1: SELECT 강화 - 구현 계획서

## 구현 순서

```
1. SELECT WHERE        (기반 - WhereEvaluator 재사용, 최소 변경)
    ↓
2. SELECT 특정 컬럼    (WHERE와 독립적이나 같은 흐름에서 처리)
    ↓
3. ORDER BY            (WHERE/컬럼 선택 후 결과에 적용)
    ↓
4. LIMIT / OFFSET      (ORDER BY 후 최종 결과에 적용)
```

SQL 표준 실행 순서(FROM → WHERE → SELECT → ORDER BY → LIMIT)와 일치한다.

### 최종 실행 파이프라인

```
readTable → WHERE 필터링 → ORDER BY 정렬 → OFFSET/LIMIT → 컬럼 프로젝션
```

---

## 핵심 설계 결정

### ConnectionHandler: regex → SqlParser(JSqlParser) 전환

현재 `parseAndHandleSelect()`는 단순 regex로 tableName만 추출한다. WHERE/ORDER BY/LIMIT/컬럼을 모두 지원하려면 regex가 비현실적이므로 **SqlParser 기반으로 전환**한다.

- `ConnectionHandler` 생성자에 `SqlParser` 의존성 추가 (기본값으로 하위 호환)
- `DbTcpServer`에서 `ConnectionHandler` 생성 시 `SqlParser` 전달

### WHERE 브릿지

`SqlParser`의 `WhereCondition`과 `common`의 `WhereClause`는 서로 다른 타입이므로, `plainSelect.where?.toString()`으로 원문 문자열을 추출하여 `WhereClause.parse()`에 전달한다.

### ParsedQuery 확장 전략

기존 `orderBy: List<String>` 유지 + `orderByColumns: List<OrderByColumn>` 별도 추가. `ExplainServiceTest`에서 `orderBy`를 6군데 이상 사용하므로 기존 필드를 변경하면 전부 깨진다. 모든 신규 필드에 기본값 설정으로 하위 호환 유지.

### API 서버: 변경 불필요

`api-server`의 `TableController`/`DbClient`는 SQL 문자열을 단순 중계하므로 변경 불필요.

---

## DELETE에서 재사용 가능한 코드

| 코드 | 위치 | SELECT에서의 재사용 |
|------|------|-------------------|
| `WhereClause.parse()` | `common/.../where/WhereClause.kt` | 그대로 재사용 |
| `WhereEvaluator.matches()` | `common/.../where/WhereEvaluator.kt` | 그대로 재사용 |
| `validateWhereColumns()` | `TableService.kt` | 그대로 재사용 (같은 클래스 내 private) |
| `compareValues()` | `WhereEvaluator.kt` | ORDER BY에서도 필요 → internal로 변경 |

---

## 기능별 상세

### 기능 1: SELECT WHERE

**현재 상태:** 파서/평가기가 이미 존재하고 DELETE에서 동작 중. `ConnectionHandler`가 WHERE를 파싱하지만 **사용하지 않음**, `TableService.select()`에 WHERE 파라미터 없음.

**데이터 흐름:**

```
"SELECT * FROM users WHERE age > 20"
       │
       ▼
[ConnectionHandler] → tableName="users", whereString="age > 20"
       │
       ▼
[TableService.select(tableName, whereString)]
  1. WhereClause.parse(whereString)
  2. validateWhereColumns()
  3. readTable() → 전체 행
  4. rows.filter { WhereEvaluator.matches() }
       │
       ▼
[Table(필터링된 rows)]
```

**변경 파일:** `TableService.kt`, `ConnectionHandler.kt`

---

### 기능 2: SELECT 특정 컬럼

**현재 상태:** `SqlParser.parseSelectColumns()`가 이미 파싱. `ConnectionHandler`가 컬럼 부분을 무시, `TableService.select()`에 컬럼 파라미터 없음.

**핵심:** WHERE 먼저, 프로젝션은 나중에. `SELECT name FROM users WHERE age > 20`에서 age가 WHERE에 필요하므로 전체 컬럼으로 필터링 후 프로젝션 적용.

**데이터 흐름:**

```
"SELECT name, email FROM users WHERE age > 20"
       │
       ▼
[TableService.select(tableName, whereString, columns)]
  1. readTable() → 전체 행 (모든 컬럼)
  2. WHERE 필터링 (모든 컬럼 포함 상태)
  3. 프로젝션 → name, email만 남김 (dataType도 함께 프로젝션)
```

**변경 파일:** `TableService.kt`, `Resolver.kt` (validateSelectColumns 추가), `ConnectionHandler.kt`

---

### 기능 3: ORDER BY

**현재 상태:** `SqlParser.parseOrderBy()`가 컬럼명만 추출하고 **ASC/DESC 정보를 버림**. 정렬 실행 로직 자체 없음.

**핵심:** ORDER BY는 프로젝션 **전**에 수행 (`SELECT name ORDER BY age`에서 age 필요). `WhereEvaluator.compareValues()`로 타입별 비교(INT는 숫자순, VARCHAR는 사전순).

**데이터 흐름:**

```
"SELECT * FROM users WHERE age > 20 ORDER BY name ASC, age DESC"
       │
       ▼
[TableService.select(tableName, whereString, columns, orderBy)]
  1. readTable() → 전체 행
  2. WHERE 필터링
  3. ORDER BY 정렬: "name" ASC, 동일 시 "age" DESC
  4. 프로젝션
```

**변경 파일:** `OrderByColumn.kt` (신규 DTO), `ParsedQuery.kt`, `SqlParser.kt`, `TableService.kt`, `Resolver.kt` (validateOrderByColumns 추가)

---

### 기능 4: LIMIT / OFFSET

**현재 상태:** 파싱/실행 모두 미구현.

**핵심:** JSqlParser의 `PlainSelect.limit`에서 추출. 실행은 `drop(offset).take(limit)`.

**데이터 흐름:**

```
"SELECT name FROM users WHERE age > 20 ORDER BY name LIMIT 10 OFFSET 20"
       │
       ▼
[TableService.select(tableName, whereString, columns, orderBy, limit, offset)]
  1. readTable() → N개 행
  2. WHERE 필터링 → M개 행
  3. ORDER BY → 정렬
  4. .drop(20).take(10) → 최대 10개
  5. 프로젝션 (name만)
```

**변경 파일:** `ParsedQuery.kt`, `SqlParser.kt`, `TableService.kt`, `ConnectionHandler.kt`

---

## TableService.select() 최종 시그니처

```kotlin
fun select(
    tableName: String,
    whereString: String? = null,
    columns: List<String>? = null,
    orderBy: List<OrderByColumn>? = null,
    limit: Int? = null,
    offset: Int? = null
): Table?
```

기본값이 모두 null이므로 기존 호출 `tableService.select(tableName)`은 변경 없이 동작한다.

---

## 구현 순서

| # | 타입 | 메시지 | 주요 변경 파일 |
|---|------|------------|--------------|
| 1 | `refactor` | WhereEvaluator.compareValues()를 internal로 변경 | `WhereEvaluator.kt` |
| 2 | `feat` | OrderByColumn DTO 생성 및 ParsedQuery 확장 | `OrderByColumn.kt`(신규), `ParsedQuery.kt` |
| 3 | `feat` | SqlParser에서 whereString, orderByColumns, limit, offset 파싱 | `SqlParser.kt`, `SqlParserTest.kt` |
| 4 | `feat` | TableService.select()에 WHERE 조건 필터링 추가 | `TableService.kt`, `TableServiceTest.kt` |
| 5 | `feat` | TableService.select()에 특정 컬럼 선택(프로젝션) 추가 | `TableService.kt`, `Resolver.kt`, `TableServiceTest.kt` |
| 6 | `feat` | TableService.select()에 ORDER BY 정렬 추가 | `TableService.kt`, `Resolver.kt`, `TableServiceTest.kt` |
| 7 | `feat` | TableService.select()에 LIMIT/OFFSET 페이지네이션 추가 | `TableService.kt`, `TableServiceTest.kt` |
| 8 | `refactor` | ConnectionHandler의 SELECT를 SqlParser 기반으로 전환 | `ConnectionHandler.kt`, `DbTcpServer.kt` |
| 9 | `test` | SELECT 풀 파이프라인 통합 테스트 추가 | `TableServiceTest.kt` |

---

### 스텝 1: `refactor: WhereEvaluator.compareValues()를 internal로 변경` ✅ 완료

ORDER BY 정렬에서 타입별 비교 로직을 재사용하기 위해 `private` → `internal`로 변경.

| 변경 파일 | 내용 |
|-----------|------|
| `common/.../where/WhereEvaluator.kt` | `compareValues()` 접근 제한자 변경, 본문 변경 없음 |

```kotlin
// 변경 전
private fun compareValues(left: String, right: String, columnType: String): Int

// 변경 후
internal fun compareValues(left: String, right: String, columnType: String): Int
```

> **주의:** Kotlin `internal`은 같은 Gradle 모듈 내에서만 접근 가능. `common` 모듈의 `internal`을 `db-server`에서 직접 호출할 수 없음. 스텝 6에서 ORDER BY 구현 시 `WhereEvaluator`에 `public` 래퍼 메서드를 추가하거나 `TableService` 내부에 별도 구현 필요.

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 2: `feat: OrderByColumn DTO 생성 및 ParsedQuery 확장`

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../db_engine/dto/OrderByColumn.kt` (신규) | `data class OrderByColumn(columnName, ascending)` |
| `db-server/.../db_engine/dto/ParsedQuery.kt` | `whereString`, `orderByColumns`, `limit`, `offset` 필드 추가 |

**OrderByColumn.kt 신규 생성:**
```kotlin
package study.db.server.db_engine.dto

data class OrderByColumn(
    val columnName: String,
    val ascending: Boolean = true   // true=ASC, false=DESC
)
```

**ParsedQuery.kt 필드 추가:**

현재 `ParsedQuery`는 4개 필드: `tableName`, `selectColumns`, `whereConditions`, `orderBy: List<String>`

`ExplainServiceTest`에서 `orderBy: List<String>` 필드를 6군데 이상 사용하므로 **기존 필드 유지**, 신규 필드를 기본값과 함께 추가:

```kotlin
data class ParsedQuery(
    val tableName: String,
    val selectColumns: List<String>,
    val whereConditions: List<WhereCondition>,
    val orderBy: List<String>,             // 기존 유지 (삭제 금지)
    // 신규 필드 (기본값 필수)
    val whereString: String? = null,
    val orderByColumns: List<OrderByColumn> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null
)
```

**검증:** `./gradlew test` (기존 생성자 호출이 4개 인자로 되어 있어도 기본값으로 동작)

---

### 스텝 3: `feat: SqlParser에서 whereString, orderByColumns, limit, offset 파싱`

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../db_engine/SqlParser.kt` | `parseQuery()` return 문에 신규 필드 추가, `parseOrderByColumns()` private 메서드 추가 |
| `db-server/.../db_engine/SqlParserTest.kt` | ASC/DESC 방향, LIMIT, OFFSET, whereString 파싱 테스트 추가 |

**SqlParser.kt 변경:**

`parseQuery()` return 문에 신규 필드 추가:
```kotlin
return ParsedQuery(
    tableName = tableName,
    selectColumns = selectColumns,
    whereConditions = whereConditions,
    orderBy = orderBy,
    // 신규 필드
    whereString = plainSelect.where?.toString(),
    orderByColumns = parseOrderByColumns(plainSelect),
    limit = plainSelect.limit?.rowCount?.toString()?.toIntOrNull(),
    offset = plainSelect.limit?.offset?.toString()?.toIntOrNull()
)
```

추가할 private 메서드:
```kotlin
private fun parseOrderByColumns(plainSelect: PlainSelect): List<OrderByColumn> {
    val orderByElements = plainSelect.orderByElements ?: return emptyList()
    return orderByElements.map { element ->
        OrderByColumn(
            columnName = element.expression.toString(),
            ascending = element.isAsc   // true=ASC (기본값), false=DESC
        )
    }
}
```

> **주의:** JSqlParser의 `Limit` 클래스에서 `rowCount`와 `offset`은 `Expression` 타입. `.toString().toIntOrNull()`로 변환 필요. `LIMIT 10 OFFSET 5`에서 offset은 `plainSelect.limit.offset`에 저장됨. 기존 `parseOrderBy()` 메서드는 삭제하지 않음.

**SqlParserTest.kt에 추가할 테스트 (Nested class):**
```kotlin
@Nested
@DisplayName("SELECT 파싱 확장 테스트")
inner class SelectParsingExtensionTest {

    @Test fun `ASC ORDER BY 파싱`() {
        val result = sqlParser.parseQuery("SELECT * FROM users ORDER BY name ASC")
        assertThat(result.orderByColumns).isEqualTo(listOf(OrderByColumn("name", true)))
    }

    @Test fun `DESC ORDER BY 파싱`() {
        val result = sqlParser.parseQuery("SELECT * FROM users ORDER BY age DESC")
        assertThat(result.orderByColumns).isEqualTo(listOf(OrderByColumn("age", false)))
    }

    @Test fun `다중 컬럼 ASC DESC 혼합 ORDER BY 파싱`() {
        val result = sqlParser.parseQuery("SELECT * FROM users ORDER BY name ASC, age DESC")
        assertThat(result.orderByColumns).isEqualTo(
            listOf(OrderByColumn("name", true), OrderByColumn("age", false))
        )
    }

    @Test fun `LIMIT 파싱`() {
        val result = sqlParser.parseQuery("SELECT * FROM users LIMIT 10")
        assertThat(result.limit).isEqualTo(10)
        assertThat(result.offset).isNull()
    }

    @Test fun `LIMIT + OFFSET 파싱`() {
        val result = sqlParser.parseQuery("SELECT * FROM users LIMIT 10 OFFSET 20")
        assertThat(result.limit).isEqualTo(10)
        assertThat(result.offset).isEqualTo(20)
    }

    @Test fun `WHERE 원문 문자열 파싱`() {
        val result = sqlParser.parseQuery("SELECT * FROM users WHERE age > 20")
        assertThat(result.whereString).isNotNull()
        assertThat(result.whereString).contains("age")
    }
}
```

검증: `./gradlew :db-server:test --tests "study.db.server.db_engine.SqlParserTest"`

---

### 스텝 4: `feat: TableService.select()에 WHERE 조건 필터링 추가`

DELETE의 `delete()` 패턴(`WhereClause.parse → validateWhereColumns → WhereEvaluator.matches`)을 그대로 재사용.

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../service/TableService.kt` | `select()`에 `whereString: String? = null` 파라미터 추가, WHERE 필터링 로직 |
| `db-server/.../service/TableServiceTest.kt` | `SelectWhereTest` inner class 추가 (8개 테스트) |

**TableService.kt 변경:**

```kotlin
// 변경 전
fun select(tableName: String): Table?

// 변경 후
fun select(
    tableName: String,
    whereString: String? = null
): Table? {
    val table = tableFileManager?.readTable(tableName) ?: tables[tableName] ?: return null

    if (whereString == null) return table

    val whereClause = WhereClause.parse(whereString)
    validateWhereColumns(table, whereClause)   // 기존 private 메서드 재사용

    val filteredRows = table.rows.filter { row ->
        WhereEvaluator.matches(Row(data = row), whereClause, table.dataType)
    }
    return table.copy(rows = filteredRows)
}
```

> `validateWhereColumns()`는 `TableService` 내 `private` 메서드로 이미 존재. `Row(data = row)` 생성 패턴은 기존 `delete()` 메서드와 동일.

**TableServiceTest.kt에 추가할 SelectWhereTest:**
```kotlin
@Nested
@DisplayName("SELECT WHERE 필터링 테스트")
inner class SelectWhereTest {

    @BeforeEach fun setUp() {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
        tableService.insert("users", mapOf("id" to "1", "name" to "Alice", "age" to "30"))
        tableService.insert("users", mapOf("id" to "2", "name" to "Bob", "age" to "25"))
        tableService.insert("users", mapOf("id" to "3", "name" to "Charlie", "age" to "35"))
    }

    @Test fun `등호 조건 WHERE 필터링`() {
        val result = tableService.select("users", whereString = "name='Alice'")
        assertThat(result!!.rows).hasSize(1)
        assertThat(result.rows[0]["name"]).isEqualTo("Alice")
    }

    @Test fun `비교 연산자 WHERE 필터링`() {
        val result = tableService.select("users", whereString = "age > 28")
        assertThat(result!!.rows).hasSize(2)  // Alice(30), Charlie(35)
    }

    @Test fun `AND 조건 WHERE 필터링`() {
        val result = tableService.select("users", whereString = "age > 28 AND name='Alice'")
        assertThat(result!!.rows).hasSize(1)
        assertThat(result.rows[0]["name"]).isEqualTo("Alice")
    }

    @Test fun `OR 조건 WHERE 필터링`() {
        val result = tableService.select("users", whereString = "id=1 OR id=3")
        assertThat(result!!.rows).hasSize(2)
    }

    @Test fun `INT 숫자 비교 (사전순 아닌 숫자순)`() {
        // age: 3, 25, 100 → 사전순이면 "100" < "25" < "3", 숫자순이면 3 < 25 < 100
        tableService.createTable("nums", mapOf("age" to "INT"))
        tableService.insert("nums", mapOf("age" to "3"))
        tableService.insert("nums", mapOf("age" to "100"))
        tableService.insert("nums", mapOf("age" to "25"))
        val result = tableService.select("nums", whereString = "age > 25")
        assertThat(result!!.rows).hasSize(1)
        assertThat(result.rows[0]["age"]).isEqualTo("100")
    }

    @Test fun `WHERE 없음 - 전체 반환`() {
        val result = tableService.select("users")
        assertThat(result!!.rows).hasSize(3)
    }

    @Test fun `존재하지 않는 컬럼 WHERE - 예외 발생`() {
        assertThrows<ColumnNotFoundException> {
            tableService.select("users", whereString = "email='test'")
        }
    }

    @Test fun `조건 만족 행 없음 - 빈 rows 반환`() {
        val result = tableService.select("users", whereString = "id=999")
        assertThat(result).isNotNull()
        assertThat(result!!.rows).isEmpty()
    }
}
```

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 5: `feat: TableService.select()에 특정 컬럼 선택(프로젝션) 추가`

WHERE 필터링 후 프로젝션 적용. `SELECT name FROM users WHERE age > 20`에서 age가 WHERE에 필요하므로 전체 컬럼 상태로 필터링 후 프로젝션.

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../validation/Resolver.kt` | `validateSelectColumns()` 추가 |
| `db-server/.../service/TableService.kt` | `columns: List<String>? = null` 파라미터 추가, 프로젝션 로직 |
| `db-server/.../service/TableServiceTest.kt` | `SelectColumnProjectionTest` inner class 추가 (5개 테스트) |

**Resolver.kt에 추가할 메서드:**
```kotlin
fun validateSelectColumns(table: Table, columns: List<String>) {
    if (columns.contains("*")) return
    columns.forEach { columnName ->
        validateColumnExists(table, columnName)  // 기존 메서드 재사용
    }
}
```

**TableService.kt 변경:**
```kotlin
fun select(
    tableName: String,
    whereString: String? = null,
    columns: List<String>? = null   // 추가
): Table? {
    val table = ...

    // 1. WHERE 필터링 (전체 컬럼 상태)
    val filteredRows = ...

    // 2. 컬럼 프로젝션 (WHERE 후)
    if (columns == null || columns == listOf("*")) {
        return table.copy(rows = filteredRows)
    }

    resolver.validateSelectColumns(table, columns)

    val projectedRows = filteredRows.map { row ->
        row.filterKeys { it in columns }
    }
    val projectedDataType = table.dataType.filterKeys { it in columns }

    return table.copy(rows = projectedRows, dataType = projectedDataType)
}
```

**TableServiceTest.kt에 추가할 SelectColumnProjectionTest:**
```kotlin
@Nested
@DisplayName("SELECT 컬럼 프로젝션 테스트")
inner class SelectColumnProjectionTest {

    @BeforeEach fun setUp() {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
        tableService.insert("users", mapOf("id" to "1", "name" to "Alice", "age" to "30"))
        tableService.insert("users", mapOf("id" to "2", "name" to "Bob", "age" to "25"))
    }

    @Test fun `특정 컬럼만 SELECT`() {
        val result = tableService.select("users", columns = listOf("name"))
        assertThat(result!!.rows[0].keys).containsExactly("name")
        assertThat(result.dataType.keys).containsExactly("name")
    }

    @Test fun `SELECT star - 전체 컬럼 반환`() {
        val result = tableService.select("users", columns = listOf("*"))
        assertThat(result!!.rows[0].keys).containsExactlyInAnyOrder("id", "name", "age")
    }

    @Test fun `존재하지 않는 컬럼 SELECT - 예외 발생`() {
        assertThrows<ColumnNotFoundException> {
            tableService.select("users", columns = listOf("email"))
        }
    }

    @Test fun `WHERE 컬럼과 SELECT 컬럼이 다른 경우 (age로 필터, name만 SELECT)`() {
        val result = tableService.select("users", whereString = "age > 28", columns = listOf("name"))
        assertThat(result!!.rows).hasSize(1)
        assertThat(result.rows[0].keys).containsExactly("name")
        assertThat(result.rows[0]["age"]).isNull()
    }

    @Test fun `dataType도 함께 프로젝션되는지 확인`() {
        val result = tableService.select("users", columns = listOf("id", "name"))
        assertThat(result!!.dataType).isEqualTo(mapOf("id" to "INT", "name" to "VARCHAR"))
    }
}
```

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 6: `feat: TableService.select()에 ORDER BY 정렬 추가`

파이프라인 순서: WHERE → **ORDER BY** → 프로젝션 (정렬 컬럼이 SELECT 컬럼에 없을 수 있으므로 프로젝션 전에 정렬)

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../validation/Resolver.kt` | `validateOrderByColumns()` 추가 |
| `db-server/.../service/TableService.kt` | `orderBy: List<OrderByColumn>? = null` 파라미터 추가, Comparator 기반 정렬 |
| `db-server/.../service/TableServiceTest.kt` | `SelectOrderByTest` inner class 추가 (8개 테스트) |

**Resolver.kt에 추가할 메서드:**
```kotlin
fun validateOrderByColumns(table: Table, orderBy: List<OrderByColumn>) {
    orderBy.forEach { orderByColumn ->
        validateColumnExists(table, orderByColumn.columnName)
    }
}
```

**TableService.kt 변경 (정렬 로직):**
```kotlin
fun select(
    tableName: String,
    whereString: String? = null,
    columns: List<String>? = null,
    orderBy: List<OrderByColumn>? = null   // 추가
): Table? {
    val table = ...

    // 1. WHERE 필터링
    val filteredRows = ...

    // 2. ORDER BY 정렬 (프로젝션 전)
    val sortedRows = if (orderBy.isNullOrEmpty()) {
        filteredRows
    } else {
        resolver.validateOrderByColumns(table, orderBy)
        filteredRows.sortedWith(
            Comparator { row1, row2 ->
                for (col in orderBy) {
                    val v1 = row1[col.columnName] ?: ""
                    val v2 = row2[col.columnName] ?: ""
                    val colType = table.dataType[col.columnName]?.uppercase() ?: "VARCHAR"
                    val cmp = compareByType(v1, v2, colType)  // TableService 내 private 메서드
                    if (cmp != 0) return@Comparator if (col.ascending) cmp else -cmp
                }
                0
            }
        )
    }

    // 3. 프로젝션 ...
}

// TableService 내 private 비교 메서드 (WhereEvaluator.compareValues()가 internal이라 common→db-server 모듈 경계로 직접 접근 불가한 경우)
private fun compareByType(left: String, right: String, columnType: String): Int {
    return when (columnType) {
        "INT" -> (left.toIntOrNull() ?: 0).compareTo(right.toIntOrNull() ?: 0)
        "TIMESTAMP" -> (left.toLongOrNull() ?: 0L).compareTo(right.toLongOrNull() ?: 0L)
        else -> left.compareTo(right)
    }
}
```

> **주의:** `WhereEvaluator.compareValues()`는 `common` 모듈의 `internal`이므로 `db-server` 모듈에서 직접 호출 불가. `TableService` 내부에 `compareByType()` 메서드를 별도 구현.

**TableServiceTest.kt에 추가할 SelectOrderByTest:**
```kotlin
@Nested
@DisplayName("SELECT ORDER BY 정렬 테스트")
inner class SelectOrderByTest {

    @BeforeEach fun setUp() {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
        tableService.insert("users", mapOf("id" to "1", "name" to "Charlie", "age" to "35"))
        tableService.insert("users", mapOf("id" to "2", "name" to "Alice", "age" to "25"))
        tableService.insert("users", mapOf("id" to "3", "name" to "Bob", "age" to "30"))
    }

    @Test fun `name ASC 정렬`() {
        val result = tableService.select("users", orderBy = listOf(OrderByColumn("name", true)))
        assertThat(result!!.rows.map { it["name"] }).containsExactly("Alice", "Bob", "Charlie")
    }

    @Test fun `name DESC 정렬`() {
        val result = tableService.select("users", orderBy = listOf(OrderByColumn("name", false)))
        assertThat(result!!.rows.map { it["name"] }).containsExactly("Charlie", "Bob", "Alice")
    }

    @Test fun `INT 타입 숫자 순서 정렬 (사전순 아님)`() {
        tableService.createTable("nums", mapOf("val" to "INT"))
        tableService.insert("nums", mapOf("val" to "3"))
        tableService.insert("nums", mapOf("val" to "100"))
        tableService.insert("nums", mapOf("val" to "25"))
        val result = tableService.select("nums", orderBy = listOf(OrderByColumn("val", true)))
        assertThat(result!!.rows.map { it["val"] }).containsExactly("3", "25", "100")
    }

    @Test fun `다중 컬럼 정렬 (name ASC, age DESC)`() {
        tableService.insert("users", mapOf("id" to "4", "name" to "Alice", "age" to "20"))
        val result = tableService.select("users",
            orderBy = listOf(OrderByColumn("name", true), OrderByColumn("age", false))
        )
        // Alice 중 age DESC: 25 먼저, 20 나중
        assertThat(result!!.rows[0]["name"]).isEqualTo("Alice")
        assertThat(result.rows[0]["age"]).isEqualTo("25")
        assertThat(result.rows[1]["age"]).isEqualTo("20")
    }

    @Test fun `ORDER BY 없음 - 삽입 순서 유지`() {
        val result = tableService.select("users")
        assertThat(result!!.rows.map { it["id"] }).containsExactly("1", "2", "3")
    }

    @Test fun `WHERE + ORDER BY 조합`() {
        val result = tableService.select("users",
            whereString = "age > 25",
            orderBy = listOf(OrderByColumn("name", true))
        )
        // age>25: Charlie(35), Bob(30) → name ASC: Bob, Charlie
        assertThat(result!!.rows.map { it["name"] }).containsExactly("Bob", "Charlie")
    }

    @Test fun `존재하지 않는 컬럼 ORDER BY - 예외 발생`() {
        assertThrows<ColumnNotFoundException> {
            tableService.select("users", orderBy = listOf(OrderByColumn("email", true)))
        }
    }

    @Test fun `VARCHAR 사전순 정렬 확인`() {
        val result = tableService.select("users", orderBy = listOf(OrderByColumn("name", true)))
        assertThat(result!!.rows.map { it["name"] }).isSortedAccordingTo(compareBy { it })
    }
}
```

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 7: `feat: TableService.select()에 LIMIT/OFFSET 페이지네이션 추가`

실행 파이프라인: WHERE → ORDER BY → **OFFSET/LIMIT** → 프로젝션

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../service/TableService.kt` | `limit: Int? = null`, `offset: Int? = null` 파라미터 추가, `drop()/take()` 로직 |
| `db-server/.../service/TableServiceTest.kt` | `SelectLimitOffsetTest` inner class 추가 (8개 테스트) |

**TableService.kt 변경 (LIMIT/OFFSET 로직):**
```kotlin
fun select(
    tableName: String,
    whereString: String? = null,
    columns: List<String>? = null,
    orderBy: List<OrderByColumn>? = null,
    limit: Int? = null,    // 추가
    offset: Int? = null    // 추가
): Table? {
    // ...WHERE 필터링, ORDER BY 정렬...

    // OFFSET/LIMIT 적용 (정렬 후, 프로젝션 전)
    val pagedRows = sortedRows
        .let { if (offset != null) it.drop(offset) else it }
        .let { if (limit != null) it.take(limit) else it }

    // ...프로젝션...
}
```

> offset이 rows 수보다 크면 `drop()`이 빈 리스트 반환 → Table(rows=[]) 정상 반환. limit이 남은 rows보다 크면 `take()`가 전부 반환. limit=0이면 빈 리스트.

**TableServiceTest.kt에 추가할 SelectLimitOffsetTest:**
```kotlin
@Nested
@DisplayName("SELECT LIMIT/OFFSET 페이지네이션 테스트")
inner class SelectLimitOffsetTest {

    @BeforeEach fun setUp() {
        tableService.createTable("items", mapOf("id" to "INT"))
        (1..10).forEach { i -> tableService.insert("items", mapOf("id" to "$i")) }
    }

    @Test fun `LIMIT만 지정`() {
        val result = tableService.select("items", limit = 3)
        assertThat(result!!.rows).hasSize(3)
        assertThat(result.rows[0]["id"]).isEqualTo("1")
    }

    @Test fun `OFFSET만 지정`() {
        val result = tableService.select("items", offset = 7)
        assertThat(result!!.rows).hasSize(3)  // id=8,9,10
    }

    @Test fun `LIMIT + OFFSET 조합`() {
        val result = tableService.select("items", limit = 3, offset = 5)
        assertThat(result!!.rows).hasSize(3)
        assertThat(result.rows[0]["id"]).isEqualTo("6")
    }

    @Test fun `LIMIT이 전체보다 큰 경우 - 전체 반환`() {
        val result = tableService.select("items", limit = 100)
        assertThat(result!!.rows).hasSize(10)
    }

    @Test fun `OFFSET이 전체보다 큰 경우 - 빈 rows 반환`() {
        val result = tableService.select("items", offset = 100)
        assertThat(result).isNotNull()
        assertThat(result!!.rows).isEmpty()
    }

    @Test fun `LIMIT = 0`() {
        val result = tableService.select("items", limit = 0)
        assertThat(result!!.rows).isEmpty()
    }

    @Test fun `ORDER BY + LIMIT 조합`() {
        val result = tableService.select("items",
            orderBy = listOf(OrderByColumn("id", false)),
            limit = 3
        )
        assertThat(result!!.rows.map { it["id"] }).containsExactly("10", "9", "8")
    }

    @Test fun `풀 파이프라인 (WHERE + ORDER BY + LIMIT + OFFSET)`() {
        // WHERE id>3(7개) → DESC 정렬(10,9,8,7,6,5,4) → OFFSET 1(9,8,7,6,5,4) → LIMIT 3(9,8,7)
        val result = tableService.select("items",
            whereString = "id > 3",
            orderBy = listOf(OrderByColumn("id", false)),
            limit = 3,
            offset = 1
        )
        assertThat(result!!.rows.map { it["id"] }).containsExactly("9", "8", "7")
    }
}
```

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 8: `refactor: ConnectionHandler의 SELECT를 SqlParser 기반으로 전환`

regex 기반 `parseAndHandleSelect()`를 `SqlParser` 기반으로 교체하여 WHERE/컬럼/ORDER BY/LIMIT/OFFSET 전체 파이프라인 연결.

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../db_engine/ConnectionHandler.kt` | 생성자에 `sqlParser: SqlParser = SqlParser()` 추가, `parseAndHandleSelect()` 전면 교체 |
| `db-server/DbTcpServer.kt` | `SqlParser()` 인스턴스 생성 후 `ConnectionHandler`에 전달 |

**ConnectionHandler.kt 생성자 변경:**
```kotlin
class ConnectionHandler(
    val connectionId: Long,
    private val socket: Socket,
    private val tableService: TableService,
    private val connectionManager: ConnectionManager? = null,
    private val explainService: ExplainService? = null,
    private val sqlParser: SqlParser = SqlParser()  // 추가 (기본값으로 기존 테스트 호환)
)
```

**`parseAndHandleSelect()` 전면 교체:**
```kotlin
private fun parseAndHandleSelect(sql: String): DbResponse {
    return ExceptionMapper.executeWithExceptionHandling(connectionId) {
        val parsed = sqlParser.parseQuery(sql)

        val columns = parsed.selectColumns.takeUnless { it == listOf("*") }
        val orderBy = parsed.orderByColumns.ifEmpty { null }

        val table = tableService.select(
            tableName = parsed.tableName,
            whereString = parsed.whereString,
            columns = columns,
            orderBy = orderBy,
            limit = parsed.limit,
            offset = parsed.offset
        ) ?: throw TableNotFoundException(parsed.tableName)

        DbResponse(success = true, data = json.encodeToString<Table>(table))
    }
}
```

**DbTcpServer.kt에서 ConnectionHandler 생성 부분 변경:**
```kotlin
val sqlParser = SqlParser()  // 추가

val handler = ConnectionHandler(
    connectionId = connectionId,
    socket = clientSocket,
    tableService = sharedTableService,
    connectionManager = connectionManager,
    explainService = explainService,
    sqlParser = sqlParser  // 추가
)
```

> **주의:** `ConnectionHandlerTest.kt`에서 기존 5개 인자로 `ConnectionHandler` 생성 시 `sqlParser`의 기본값으로 동작하므로 기존 테스트 변경 불필요.

검증: `./gradlew test` (전체 통과)

---

### 스텝 9: `test: SELECT 풀 파이프라인 통합 테스트 추가`

WHERE + 컬럼 선택 + ORDER BY + LIMIT/OFFSET 전체 조합 E2E 테스트.

| 변경 파일 | 내용 |
|-----------|------|
| `db-server/.../service/TableServiceTest.kt` | `SelectFullPipelineTest` inner class 추가 (4개 테스트) |

**테스트 픽스처 (products 테이블):**
```
id | name      | category  | price
1  | Apple     | fruit     | 300
2  | Banana    | fruit     | 150
3  | Carrot    | vegetable | 200
4  | Durian    | fruit     | 800
5  | Eggplant  | vegetable | 350
```

**TableServiceTest.kt에 추가할 SelectFullPipelineTest:**
```kotlin
@Nested
@DisplayName("SELECT 풀 파이프라인 통합 테스트")
inner class SelectFullPipelineTest {

    @BeforeEach fun setUp() {
        tableService.createTable("products",
            mapOf("id" to "INT", "name" to "VARCHAR", "category" to "VARCHAR", "price" to "INT")
        )
        tableService.insert("products", mapOf("id" to "1", "name" to "Apple",    "category" to "fruit",     "price" to "300"))
        tableService.insert("products", mapOf("id" to "2", "name" to "Banana",   "category" to "fruit",     "price" to "150"))
        tableService.insert("products", mapOf("id" to "3", "name" to "Carrot",   "category" to "vegetable", "price" to "200"))
        tableService.insert("products", mapOf("id" to "4", "name" to "Durian",   "category" to "fruit",     "price" to "800"))
        tableService.insert("products", mapOf("id" to "5", "name" to "Eggplant", "category" to "vegetable", "price" to "350"))
    }

    @Test fun `특정 컬럼 + WHERE + ORDER BY + LIMIT`() {
        // SELECT name, price FROM products WHERE category='fruit' ORDER BY price DESC LIMIT 2
        val result = tableService.select("products",
            whereString = "category='fruit'",
            columns = listOf("name", "price"),
            orderBy = listOf(OrderByColumn("price", false)),
            limit = 2
        )
        assertThat(result!!.rows).hasSize(2)
        assertThat(result.rows[0]).isEqualTo(mapOf("name" to "Durian", "price" to "800"))
        assertThat(result.rows[1]).isEqualTo(mapOf("name" to "Apple",  "price" to "300"))
        assertThat(result.rows[0]["category"]).isNull()  // 프로젝션으로 제거됨
        assertThat(result.dataType).isEqualTo(mapOf("name" to "VARCHAR", "price" to "INT"))
    }

    @Test fun `ORDER BY + OFFSET + LIMIT (2페이지 조회)`() {
        val result = tableService.select("products",
            orderBy = listOf(OrderByColumn("id", true)),
            limit = 2,
            offset = 2
        )
        assertThat(result!!.rows).hasSize(2)
        assertThat(result.rows[0]["id"]).isEqualTo("3")  // Carrot
        assertThat(result.rows[1]["id"]).isEqualTo("4")  // Durian
    }

    @Test fun `WHERE + ORDER BY (LIMIT 없음)`() {
        val result = tableService.select("products",
            whereString = "category='vegetable'",
            orderBy = listOf(OrderByColumn("price", true))
        )
        assertThat(result!!.rows).hasSize(2)
        assertThat(result.rows[0]["name"]).isEqualTo("Carrot")    // price=200
        assertThat(result.rows[1]["name"]).isEqualTo("Eggplant")  // price=350
    }

    @Test fun `WHERE + LIMIT (ORDER BY 없음, 삽입 순서 유지)`() {
        val result = tableService.select("products",
            whereString = "category='fruit'",
            limit = 2
        )
        assertThat(result!!.rows).hasSize(2)
        assertThat(result.rows[0]["name"]).isEqualTo("Apple")   // 삽입 순서 첫 번째 fruit
        assertThat(result.rows[1]["name"]).isEqualTo("Banana")
    }
}
```

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`
