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

### 스텝 1: `refactor: WhereEvaluator.compareValues()를 internal로 변경`

ORDER BY 정렬에서 타입별 비교 로직을 재사용하기 위해 private → internal로 변경.

| 변경 파일 | 내용 |
|-----------|------|
| `WhereEvaluator.kt` | `compareValues()` 접근 제한자 변경, 본문 변경 없음 |

검증: `./gradlew test` (기존 테스트 모두 통과)

---

### 스텝 2: `feat: OrderByColumn DTO 생성 및 ParsedQuery 확장`

| 변경 파일 | 내용 |
|-----------|------|
| `dto/OrderByColumn.kt` (신규) | `data class OrderByColumn(columnName, ascending)` |
| `dto/ParsedQuery.kt` | `whereString`, `orderByColumns`, `limit`, `offset` 필드 추가 (모두 기본값) |

검증: `./gradlew test` (기존 생성자 호출이 기본값으로 동작)

---

### 스텝 3: `feat: SqlParser에서 whereString, orderByColumns, limit, offset 파싱`

| 변경 파일 | 내용 |
|-----------|------|
| `SqlParser.kt` | `parseQuery()`에서 신규 필드 채움, `parseOrderByColumns()` 메서드 추가 |
| `SqlParserTest.kt` | ASC/DESC 방향, LIMIT, OFFSET, whereString 테스트 추가 |

주의: JSqlParser 버전에 따라 `PlainSelect.limit` API 차이 가능. 기존 `parseOrderBy()`는 건드리지 않음.

검증: `./gradlew :db-server:test --tests "study.db.server.db_engine.SqlParserTest"`

---

### 스텝 4: `feat: TableService.select()에 WHERE 조건 필터링 추가`

DELETE 패턴(`WhereClause.parse → validateWhereColumns → WhereEvaluator.matches`)을 재사용.

| 변경 파일 | 내용 |
|-----------|------|
| `TableService.kt` | `select()`에 `whereString` 파라미터 추가, WHERE 필터링 로직 |
| `TableServiceTest.kt` | `SelectWhereTest` 8개 테스트 |

테스트 케이스: 등호/비교 연산자/AND/OR/INT 숫자 비교/WHERE 없음(전체)/존재하지 않는 컬럼/조건 만족 없음

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 5: `feat: TableService.select()에 특정 컬럼 선택(프로젝션) 추가`

| 변경 파일 | 내용 |
|-----------|------|
| `Resolver.kt` | `validateSelectColumns()` 추가 |
| `TableService.kt` | `columns` 파라미터 추가, 프로젝션 로직 (dataType도 함께) |
| `TableServiceTest.kt` | `SelectColumnProjectionTest` 5개 테스트 |

테스트 케이스: 특정 컬럼만/SELECT */존재하지 않는 컬럼/WHERE 컬럼 ≠ SELECT 컬럼/dataType 프로젝션

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 6: `feat: TableService.select()에 ORDER BY 정렬 추가`

파이프라인 순서 변경: WHERE → **ORDER BY** → 프로젝션 (정렬 컬럼이 SELECT에 없을 수 있으므로)

| 변경 파일 | 내용 |
|-----------|------|
| `Resolver.kt` | `validateOrderByColumns()` 추가 |
| `TableService.kt` | `orderBy` 파라미터 추가, Comparator 기반 정렬 |
| `TableServiceTest.kt` | `SelectOrderByTest` 8개 테스트 |

테스트 케이스: ASC/DESC/다중 컬럼/INT 숫자 순서/VARCHAR 사전순/ORDER BY 없음/WHERE+ORDER BY/존재하지 않는 컬럼

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 7: `feat: TableService.select()에 LIMIT/OFFSET 페이지네이션 추가`

| 변경 파일 | 내용 |
|-----------|------|
| `TableService.kt` | `limit`, `offset` 파라미터 추가, `drop()/take()` 로직 |
| `TableServiceTest.kt` | `SelectLimitOffsetTest` 8개 테스트 |

테스트 케이스: LIMIT만/OFFSET만/LIMIT+OFFSET/LIMIT>전체/OFFSET>전체/LIMIT 0/ORDER BY+LIMIT/풀 파이프라인

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`

---

### 스텝 8: `refactor: ConnectionHandler의 SELECT를 SqlParser 기반으로 전환`

regex 기반 → SqlParser(JSqlParser) 기반으로 교체하여 전체 파이프라인 연결.

| 변경 파일 | 내용 |
|-----------|------|
| `ConnectionHandler.kt` | 생성자에 `sqlParser` 추가 (기본값), `parseAndHandleSelect()` 전면 교체 |
| `DbTcpServer.kt` | SqlParser 인스턴스 생성 및 ConnectionHandler에 전달 |

주의: `SqlParser()`에 기본값 설정으로 기존 테스트의 생성자 호출이 깨지지 않음.

검증: `./gradlew test` (전체 통과)

---

### 스텝 9: `test: SELECT 풀 파이프라인 통합 테스트 추가`

WHERE + 컬럼 선택 + ORDER BY + LIMIT/OFFSET 전체 조합 E2E 테스트.

| 변경 파일 | 내용 |
|-----------|------|
| `TableServiceTest.kt` | `SelectFullPipelineTest` 4개 테스트 |

테스트 케이스:
1. `SELECT name, price FROM products WHERE category='fruit' ORDER BY price DESC LIMIT 2`
2. ORDER BY + OFFSET + LIMIT (페이지네이션 2페이지 조회)
3. WHERE + ORDER BY (LIMIT 없음)
4. WHERE + LIMIT (ORDER BY 없음)

검증: `./gradlew :db-server:test --tests "study.db.server.service.TableServiceTest"`
