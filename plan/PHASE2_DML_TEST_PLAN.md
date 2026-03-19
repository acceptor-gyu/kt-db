# Phase 2 DML 테스트 계획 — UPDATE & INSERT 다중 행

## 테스트 대상 요약

**Phase 2 DML 확장** — 기존 INSERT(단일)/DELETE 구현을 기반으로 두 가지 기능이 추가됨.

| 기능 | 구현 상태 | 핵심 메서드 |
|------|-----------|------------|
| UPDATE | 완료 | `TableService.update()`, `TableFileManager.updateRows()`, `Resolver.validateUpdateData()` |
| INSERT 다중 행 | 완료 | `TableService.insertBatch()`, `ConnectionHandler.extractValueGroups()` |

**기존 코드와의 변경점:**
- `TableService`에 `update()`, `insertBatch()`, `tableLocks(ReentrantLock)` 추가
- `ConnectionHandler.processRequest()`에 `UPDATE` 분기 및 `INSERT` 다중 VALUES 처리 추가
- `Resolver.validateUpdateData()` 신규 추가

---

## 테스트 범위

### 포함 범위

```
[SQL Parsing Test]  ConnectionHandler — UPDATE/INSERT 다중 행 파싱, 라우팅, 응답 메시지
[Unit Test]         Resolver.validateUpdateData — SET 컬럼/타입 검증
[Unit Test]         TableService.update — 메모리 기반 UPDATE 동작 (fileManager=null)
[Unit Test]         TableService.insertBatch — 메모리 기반 다중 삽입, 원자성
[Integration Test]  TableService + TableFileManager — UPDATE/insertBatch 파일 I/O, 재시작 후 복원
```

### 제외 범위

- SELECT, DELETE, VACUUM — 기존 테스트에서 커버됨
- WHERE 조건 파싱 자체 (`WhereClause`, `WhereEvaluator`) — common 모듈, 별도 커버
- Elasticsearch EXPLAIN — 별도 통합 테스트 존재
- 동시성 부하 테스트 (`ReentrantLock` 경합) — Phase 2 범위 외

---

## 기능별 테스트 시나리오

### UPDATE

#### 성공 시나리오
- WHERE 조건에 맞는 단일 행의 단일 컬럼을 업데이트한다
- WHERE 조건에 맞는 복수 행을 동시에 업데이트한다
- WHERE 없이 전체 테이블의 모든 행을 업데이트한다
- SET에 복수 컬럼을 동시에 지정하여 업데이트한다
- 업데이트 후 반환값이 `"Updated N row(s)"` 형식임을 확인한다
- 각 데이터 타입(INT, VARCHAR, BOOLEAN, TIMESTAMP)의 컬럼을 업데이트한다

#### 실패 시나리오
- SET에 스키마에 정의되지 않은 컬럼을 지정하면 `ColumnNotFoundException`이 발생한다
- SET 값의 타입이 컬럼 타입과 불일치하면 `TypeMismatchException`이 발생한다
- 존재하지 않는 테이블에 UPDATE를 시도하면 `IllegalStateException`이 발생한다
- SET 절이 비어있는 구문(`UPDATE users SET WHERE id=1`)은 400 응답을 반환한다
- 테이블명이 누락된 구문(`UPDATE SET name='x'`)은 400 응답을 반환한다
- WHERE 절에 존재하지 않는 컬럼이 사용되면 `ColumnNotFoundException`이 발생한다

#### 엣지 케이스
- WHERE 조건에 매칭되는 행이 없을 때 반환값 `0`, 예외 없이 성공 응답을 반환한다
- 동일 컬럼에 동일 값을 UPDATE해도 정상 처리된다 (파일 기반에서 version 증가)
- 삭제된 행(tombstone)은 UPDATE 대상에서 제외된다
- UPDATE 후 재시작 시 변경 사항이 파일에 유지된다

---

### INSERT 다중 행

#### 성공 시나리오
- VALUES 절에 2개 이상의 행을 지정하여 모두 삽입된다
- 응답 메시지가 `"Data inserted (N rows)"` 형식임을 확인한다
- 단일 행 INSERT는 기존과 동일하게 `"Data inserted"` 응답을 반환한다 (regression)
- 다중 행 삽입 후 SELECT 결과에 모든 삽입 행이 반영된다
- 다양한 데이터 타입이 섞인 다중 행을 삽입한다

#### 실패 시나리오
- 다중 행 중 임의의 행이 존재하지 않는 컬럼을 포함하면 전체 삽입이 실패한다 (원자성)
- 다중 행 중 임의의 행이 타입 불일치 값을 포함하면 전체 삽입이 실패한다 (원자성)
- 존재하지 않는 테이블에 insertBatch를 시도하면 `IllegalStateException`이 발생한다
- `VALUES ()` 빈 괄호 구문은 400 응답을 반환한다

#### 엣지 케이스
- VARCHAR 값에 쉼표(`,`)가 포함된 경우 토크나이저가 올바르게 그룹을 분리한다
- VARCHAR 값에 괄호(`()`)가 포함된 경우 토크나이저가 올바르게 그룹을 분리한다
- 쌍따옴표/홑따옴표/따옴표 없는 값 세 가지 형식이 혼재해도 올바르게 파싱된다
- `insertBatch()`는 단일 `writeTable()` 1회 호출로 처리된다 (N개 단일 insert 대비 I/O 최소화)
- 단일 행 INSERT 이후 insertBatch 추가 시 데이터 일관성이 유지된다

---

## 테스트 케이스 목록

### Unit Test — `TableServiceTest.kt` (`@Nested` 클래스 추가)

#### UpdateTest

| ID | 테스트 목적 | 사전 조건 | 입력값 | 기대 결과 |
|----|------------|----------|--------|----------|
| U-01 | WHERE 조건에 맞는 단일 행 업데이트 | `users(id INT, name VARCHAR)`, 행 3개 삽입 | `setValues={"name":"Bobby"}`, `whereString="id=2"` | 반환값 `1`, id=2 행만 변경, 나머지 불변 |
| U-02 | WHERE 없이 전체 행 업데이트 | `users`, 행 3개 삽입 | `setValues={"name":"X"}`, `whereString=null` | 반환값 `3`, 전체 행 변경 |
| U-03 | WHERE 매칭 없을 때 0 반환, 성공 | `users`, 행 2개 (id=1,2) | `whereString="id=99"` | 반환값 `0`, 예외 없음, 기존 행 불변 |
| U-04 | 존재하지 않는 컬럼 SET | `users(id INT, name VARCHAR)` | `setValues={"unknown":"v"}` | `ColumnNotFoundException` |
| U-05 | INT 컬럼에 비정수 값 SET | `users(id INT, name VARCHAR)` | `setValues={"id":"not-a-number"}` | `TypeMismatchException` |
| U-06 | WHERE 절에 존재하지 않는 컬럼 | `users`, 행 1개 | `setValues={"name":"B"}`, `whereString="age=25"` | `ColumnNotFoundException` |
| U-07 | 존재하지 않는 테이블 UPDATE | 테이블 없음 | `tableName="nonexistent"` | `IllegalStateException` |
| U-08 | 복수 컬럼 동시 UPDATE | `products(id INT, name VARCHAR, price INT)` | `setValues={"name":"New","price":"999"}` | 두 컬럼 모두 변경 |
| U-09 | 복수 행이 WHERE에 매칭될 때 | `users`, 동일 `status` 값 3행 | `whereString="status=active"` | 매칭된 행 수 반환 |

#### InsertBatchTest

| ID | 테스트 목적 | 사전 조건 | 입력값 | 기대 결과 |
|----|------------|----------|--------|----------|
| B-01 | 다중 행 삽입 기본 동작 | `users(id INT, name VARCHAR)` | rows 3개 | 예외 없음, select 결과 3행 |
| B-02 | 원자성 — 마지막 행 타입 오류 | `users` | rows 2개, 2번째 `id="bad"` | `TypeMismatchException`, 테이블 행 0개 |
| B-03 | 원자성 — 첫 번째 행 타입 오류 | `users` | rows 2개, 1번째 `id="bad"` | `TypeMismatchException`, 테이블 행 0개 |
| B-04 | 원자성 — 존재하지 않는 컬럼 포함 | `users` | rows 2개, 2번째 `unknown_col` 포함 | `ColumnNotFoundException`, 테이블 행 0개 |
| B-05 | 존재하지 않는 테이블 insertBatch | 테이블 없음 | `tableName="nonexistent"` | `IllegalStateException` |
| B-06 | 단일 insert + insertBatch 혼합 후 일관성 | `users`, 단일 insert 1행 | insertBatch 2행 | 총 3행, 순서 유지 |

---

### Integration Test — `TableServicePersistenceTest.kt` (`@Nested` 클래스 추가)

#### UpdatePersistenceTest

| ID | 테스트 목적 | 실행 절차 | 기대 결과 |
|----|------------|----------|----------|
| UP-01 | UPDATE 후 파일에 변경 반영 | `update()` → `tableFileManager.readTable()` 직접 읽기 | 파일 읽기 결과에 변경 값 반영 |
| UP-02 | UPDATE 후 재시작 시 변경 유지 | `update()` → `TableService(tableFileManager)` 재생성 → `select()` | 재시작 후에도 변경 값 유지 |
| UP-03 | WHERE 없는 전체 UPDATE 파일 반영 | rows 3개 삽입 → `update(whereString=null)` → 파일 직접 읽기 | 파일 내 활성 행 3개 모두 변경 |
| UP-04 | tombstone 행은 UPDATE 미적용 | rows 3개 삽입 → 1행 delete → `update(whereString=null)` | `updatedCount=2`, tombstone 수 유지 |

#### InsertBatchPersistenceTest

| ID | 테스트 목적 | 실행 절차 | 기대 결과 |
|----|------------|----------|----------|
| BP-01 | insertBatch 후 파일에 저장 | `insertBatch(3행)` → `tableFileManager.readTable()` | 파일 읽기 결과 3행, 데이터 일치 |
| BP-02 | insertBatch 후 재시작 시 복원 | `insertBatch(2행)` → 재시작 → `select()` | 재시작 후 2행 복원 |
| BP-03 | 대용량 insertBatch (100행) | rows 100개 → `insertBatch()` → 파일 읽기 | 100행, 임의 인덱스 데이터 일치 |

---

### SQL Parsing Test — `ConnectionHandlerTest.kt` (기존 파일 확장 또는 신규)

> `ConnectionHandler`는 소켓 의존성이 있으므로, `processRequest()`를 reflection으로 호출하거나
> `TableService` 실제 인스턴스와 함께 테스트 전용 helper를 사용한다.
> 기존 `ConnectionHandlerTest.kt` 패턴을 확인 후 일관된 방식으로 적용한다.

#### UPDATE SQL 파싱

| ID | 입력 SQL | 기대 결과 |
|----|----------|----------|
| SP-01 | `UPDATE users SET name='Bob' WHERE id=1` | `DbResponse(success=true, message="Updated N row(s)")` |
| SP-02 | `UPDATE users SET status='active'` (WHERE 없음) | `whereString=null`로 처리, 성공 응답 |
| SP-03 | `UPDATE users SET name='Alice', age=30 WHERE id=1` | `setValues={"name":"Alice","age":"30"}` 파싱 |
| SP-04 | `UPDATE users SET name="double" WHERE id=1` | 쌍따옴표 값 정상 파싱 |
| SP-05 | `UPDATE SET name='x'` (테이블명 누락) | `DbResponse(success=false, errorCode=400)` |
| SP-06 | `UPDATE users SET WHERE id=1` (SET 절 비어있음) | `DbResponse(success=false, errorCode=400)` |

#### INSERT 다중 행 SQL 파싱

| ID | 입력 SQL | 기대 결과 |
|----|----------|----------|
| SP-07 | `INSERT INTO users VALUES (id=1, name='A'), (id=2, name='B')` | `DbResponse(success=true, message="Data inserted (2 rows)")` |
| SP-08 | `INSERT INTO users VALUES (id=1, name='A')` (단일 행) | `DbResponse(success=true, message="Data inserted")` (regression) |
| SP-09 | `INSERT INTO users VALUES (id=1, name='Alice, Bob'), (id=2, name='C')` | 그룹 2개로 분리, `name="Alice, Bob"` |
| SP-10 | `INSERT INTO users VALUES (id=1, name='(test)'), (id=2, name='ok')` | 그룹 2개로 분리, `name="(test)"` |
| SP-11 | `INSERT INTO users VALUES (id=1, name="d"), (id=2, name='s'), (id=3, name=p)` | 세 가지 따옴표 형식 파싱 |
| SP-12 | `INSERT INTO users (id=1)` (VALUES 키워드 누락) | `DbResponse(success=false, errorCode=400)` |

---

## 테스트 우선순위

| 우선순위 | ID 목록 | 이유 |
|---------|---------|------|
| **critical** | U-01, U-02, U-03, U-04, U-05, B-01, B-02, B-03 | 핵심 동작 정확성, 회귀 방지 필수 |
| **high** | U-06, U-07, U-08, U-09, B-04, B-05, UP-01, UP-02, BP-01, BP-02, SP-07, SP-08, SP-09, SP-10 | 엣지 케이스 및 퍼시스턴스 보장 |
| **medium** | UP-03, UP-04, BP-03, SP-01~SP-06, SP-11 | SQL 파싱 견고성, 파일 레벨 검증 |
| **low** | SP-12, B-06 | 비정상 입력, 혼합 동작 검증 |

---

## 자동화 추천 대상

### 최우선 (regression 방지)

```
TableServiceTest.kt (기존 파일에 @Nested 추가)
├── UpdateTest       — U-01 ~ U-09
└── InsertBatchTest  — B-01 ~ B-06
```

### 통합 테스트 (파일 I/O 검증)

```
TableServicePersistenceTest.kt (기존 파일에 @Nested 추가)
├── UpdatePersistenceTest      — UP-01 ~ UP-04
└── InsertBatchPersistenceTest — BP-01 ~ BP-03
```

### 파싱 테스트 (SQL 문자열 입력 검증)

```
ConnectionHandlerTest.kt (기존 파일 확장 또는 신규)
├── UpdateParsingTest       — SP-01 ~ SP-06
└── InsertBatchParsingTest  — SP-07 ~ SP-12
```

---

## 작성 패턴 (기존 코드 기준)

```kotlin
// 픽스처 설정 — Unit Test
@BeforeEach
fun setup() {
    tableService = TableService()
}

// 픽스처 설정 — Integration Test
@BeforeEach
fun setUp() {
    tempDir = createTempDir("phase2_dml_test")
    val rowEncoder = RowEncoder(IntFieldEncoder(), VarcharFieldEncoder(), BooleanFieldEncoder(), TimestampFieldEncoder())
    tableFileManager = TableFileManager(tempDir, rowEncoder)
    tableService = TableService(tableFileManager)
}

@AfterEach
fun tearDown() {
    tempDir.deleteRecursively()
}

// 구조 예시
@Nested
@DisplayName("UPDATE 테스트")
inner class UpdateTest {

    @BeforeEach
    fun setupTable() {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
        tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
        tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))
        tableService.insert("users", mapOf("id" to "3", "name" to "Charlie"))
    }

    @Test
    @DisplayName("WHERE 조건에 맞는 단일 행 업데이트")
    fun `updates single row matching WHERE condition`() {
        // Given: id=2 행이 존재함

        // When: id=2의 name을 Bobby로 업데이트
        val updatedCount = tableService.update("users", mapOf("name" to "Bobby"), "id=2")

        // Then: 1행 업데이트, id=2만 변경됨
        assertEquals(1, updatedCount)
        val result = tableService.select("users")
        assertTrue(result?.rows?.any { it["id"] == "2" && it["name"] == "Bobby" } ?: false)
        assertTrue(result?.rows?.any { it["id"] == "1" && it["name"] == "Alice" } ?: false)
    }

    @Test
    @DisplayName("존재하지 않는 컬럼 SET 시 ColumnNotFoundException")
    fun `throws ColumnNotFoundException when SET column does not exist`() {
        assertThrows<ColumnNotFoundException> {
            tableService.update("users", mapOf("unknown" to "value"), null)
        }
    }
}
```
