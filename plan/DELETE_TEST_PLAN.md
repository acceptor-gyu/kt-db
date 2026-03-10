# DELETE Record 테스트 플랜

## 1. 기존 테스트 현황 분석

### 1.1 현재 DELETE 관련 테스트 (7개 - 모두 메모리 기반)

`TableServiceTest.kt`의 `DeleteTest` 내부 클래스에 다음 7개 테스트가 존재한다:

| 테스트명 | 검증 내용 | 한계 |
|---------|----------|------|
| `deletes rows matching WHERE condition` | id=2 조건 삭제, 삭제 수 1 확인 | 메모리 전용, SELECT 후 결과 미검증 |
| `deletes all rows when no WHERE clause` | WHERE 없이 전체 삭제 | 메모리 전용 |
| `returns count of deleted rows` | name='Alice' 삭제 수 1 | 메모리 전용 |
| `throws exception when table does not exist` | 미존재 테이블 예외 | - |
| `returns zero when no rows match condition` | 매칭 없을 때 0 반환 | 메모리 전용 |
| `throws exception for invalid WHERE clause` | 잘못된 구문 예외 | - |
| `supports both single and double quotes in WHERE` | 따옴표 지원 | 메모리 전용 |

### 1.2 미검증 영역 (GAP 분석)

- **파일 기반 DELETE**: `TableServicePersistenceTest`에 DELETE 테스트 전혀 없음
- **Tombstone 검증**: DELETE 후 파일 내 tombstone 행 존재 여부 미검증
- **DELETE 후 SELECT**: 삭제 후 조회 시 삭제된 행이 제외되는지 미검증 (메모리에서도 SELECT 결과 미확인)
- **복합 WHERE**: AND, OR, 비교 연산자(`>`, `<`, `>=`, `<=`, `!=`) DELETE 테스트 없음
- **DELETE + VACUUM**: DELETE 후 VACUUM 실행 시 tombstone 물리적 제거 미검증
- **DELETE + 재INSERT**: 삭제 후 동일 데이터 재삽입 시나리오 미검증
- **타입 기반 비교**: INT 숫자 비교 vs VARCHAR 문자열 비교 DELETE 미검증
- **서버 재시작 후 DELETE 데이터 유지**: Persistence 테스트에 DELETE 시나리오 없음

### 1.3 기존 테스트 패턴 분석

**공통 패턴:**
- `@DisplayName` 한국어 사용
- `@Nested` inner class로 테스트 그룹화
- `@BeforeEach`에서 setUp (테이블 생성, 임시 디렉토리 등)
- `@AfterEach`에서 tearDown (임시 디렉토리 삭제)
- Given/When/Then 주석 스타일
- 백틱 메서드명 (Kotlin 스타일)
- `assertEquals`, `assertTrue`, `assertNotNull`, `assertThrows` 사용

**Persistence 테스트 셋업 패턴 (`TableServicePersistenceTest`):**
```kotlin
private lateinit var tempDir: File
private lateinit var tableFileManager: TableFileManager
private lateinit var tableService: TableService

@BeforeEach
fun setUp() {
    tempDir = createTempDir("...")
    val rowEncoder = RowEncoder(IntFieldEncoder(), VarcharFieldEncoder(), BooleanFieldEncoder(), TimestampFieldEncoder())
    tableFileManager = TableFileManager(tempDir, rowEncoder)
    tableService = TableService(tableFileManager)
}
```

**VACUUM 테스트 셋업 패턴 (`VacuumServiceTest`, `VacuumIntegrationTest`):**
- `@TempDir` 사용 (JUnit5 임시 디렉토리)
- `createTableWithDeletedRows()` 헬퍼 메서드로 tombstone 포함 데이터 직접 파일 기록
- `RowEncoder`를 사용하여 바이너리 인코딩

---

## 2. Task별 테스트 케이스 목록

### Task 4-1: 파일 기반 DELETE 테스트

**테스트 파일**: `TableServicePersistenceTest.kt` (기존)에 `@Nested` inner class 추가

| # | 테스트명 | 우선순위 | 설명 |
|---|---------|---------|------|
| 1 | `DELETE 후 SELECT 시 삭제된 행이 보이지 않음` | **Critical** | DELETE 실행 후 select()로 조회하면 삭제된 행이 결과에 포함되지 않는지 검증 |
| 2 | `DELETE 후 파일에서 직접 읽어도 삭제된 행이 보이지 않음` | **Critical** | DELETE 후 tableFileManager.readTable()로 직접 읽어도 삭제된 행이 필터링되는지 검증 |
| 3 | `DELETE 후 파일 내 tombstone 행이 존재함` | **High** | DELETE 후 getTableStatistics()로 deletedRows > 0인지 확인하여 tombstone이 물리적으로 존재함을 검증 |
| 4 | `DELETE 후 재INSERT 시 정상 동작` | **High** | DELETE로 행 삭제 후 동일 데이터를 INSERT하면 새 행으로 정상 삽입되는지 검증 |
| 5 | `DELETE 후 서버 재시작 시 삭제 상태 유지` | **High** | DELETE 실행 후 TableService를 새로 생성(재시작 시뮬레이션)해도 삭제된 행이 보이지 않는지 검증 |
| 6 | `전체 행 DELETE 후 파일 내 모든 행이 tombstone` | **Medium** | WHERE 없이 전체 삭제 후 getTableStatistics()로 모든 행이 deleted인지 확인 |
| 7 | `빈 테이블에서 DELETE 시 0 반환` | **Medium** | 행이 없는 테이블에서 DELETE 실행 시 0 반환, 에러 없음 |
| 8 | `이미 삭제된 행 재삭제 시 0 반환` | **Medium** | 동일 조건으로 DELETE 2회 실행 시 두 번째는 0 반환 |

**상세 Given/When/Then:**

**테스트 1: DELETE 후 SELECT 시 삭제된 행이 보이지 않음**
- Given: users 테이블에 Alice(id=1), Bob(id=2), Charlie(id=3) 삽입 (파일 기반 TableService)
- When: `tableService.delete("users", "id=2")` 실행
- Then: `tableService.select("users")`의 rows.size == 2, Bob이 결과에 없음

**테스트 2: DELETE 후 파일에서 직접 읽어도 삭제된 행이 보이지 않음**
- Given: users 테이블에 3개 행 삽입
- When: `tableService.delete("users", "name='Bob'")` 실행
- Then: `tableFileManager.readTable("users")`의 rows.size == 2, Bob 데이터 없음

**테스트 3: DELETE 후 파일 내 tombstone 행이 존재함**
- Given: users 테이블에 3개 행 삽입
- When: `tableService.delete("users", "id=1")` 실행
- Then: `tableFileManager.getTableStatistics("users")`에서 totalRows == 3, deletedRows == 1, activeRows == 2

**테스트 4: DELETE 후 재INSERT 시 정상 동작**
- Given: users 테이블에 Alice(id=1) 삽입 후 `delete("users", "id=1")` 실행
- When: `tableService.insert("users", mapOf("id" to "1", "name" to "Alice_new"))` 실행
- Then: `tableService.select("users")`의 rows에 Alice_new가 존재, 이전 Alice는 없음

**테스트 5: DELETE 후 서버 재시작 시 삭제 상태 유지**
- Given: users 테이블에 3개 행 삽입 후 `delete("users", "id=2")` 실행
- When: `val newTableService = TableService(tableFileManager)` (재시작 시뮬레이션)
- Then: `newTableService.select("users")`의 rows.size == 2, id=2 행 없음

**테스트 6: 전체 행 DELETE 후 파일 내 모든 행이 tombstone**
- Given: users 테이블에 3개 행 삽입
- When: `tableService.delete("users", null)` 실행
- Then: `tableFileManager.getTableStatistics("users")`에서 deletedRows == 3, activeRows == 0

**테스트 7: 빈 테이블에서 DELETE 시 0 반환**
- Given: users 테이블 생성 (행 없음)
- When: `tableService.delete("users", "id=1")` 실행
- Then: deletedCount == 0, 예외 없음

**테스트 8: 이미 삭제된 행 재삭제 시 0 반환**
- Given: users 테이블에 Alice(id=1) 삽입 후 `delete("users", "id=1")` 실행
- When: `tableService.delete("users", "id=1")` 재실행
- Then: deletedCount == 0

---

### Task 4-2: 복합 WHERE 조건 DELETE 테스트

**테스트 파일**: `TableServiceTest.kt` (기존) `DeleteTest` 내에 추가 또는 별도 `@Nested` inner class `ComplexWhereDeleteTest` 생성

메모리 기반 테스트로 충분. WhereClause 파싱과 WhereEvaluator 평가가 올바르게 동작하는지 검증이 목적.

| # | 테스트명 | 우선순위 | 설명 |
|---|---------|---------|------|
| 1 | `AND 조건 DELETE - 두 조건 모두 만족하는 행만 삭제` | **High** | age > 28 AND name='Alice' |
| 2 | `OR 조건 DELETE - 하나라도 만족하는 행 삭제` | **Medium** | id=1 OR id=3 |
| 3 | `비교 연산자 > DELETE` | **High** | id > 2 인 행 삭제 |
| 4 | `비교 연산자 < DELETE` | **High** | id < 2 인 행 삭제 |
| 5 | `비교 연산자 >= DELETE` | **Medium** | id >= 2 인 행 삭제 |
| 6 | `비교 연산자 <= DELETE` | **Medium** | id <= 2 인 행 삭제 |
| 7 | `비교 연산자 != DELETE` | **Medium** | name != 'Alice' 인 행 삭제 |
| 8 | `INT 타입 숫자 비교 DELETE` | **High** | age > 25 (숫자 비교, "3" > "25" 문자열 비교와 다른 결과 확인) |
| 9 | `VARCHAR 타입 문자열 비교 DELETE` | **Medium** | name > 'B' (사전순 비교) |
| 10 | `존재하지 않는 컬럼 WHERE 조건 시 예외` | **High** | email='test' (email 컬럼 미정의) |
| 11 | `AND 조건으로 매칭되는 행이 없을 때 0 반환` | **Low** | 두 조건을 동시에 만족하는 행 없음 |

**상세 Given/When/Then:**

**테스트 1: AND 조건 DELETE**
- Given: users 테이블 (id INT, name VARCHAR, age INT), 행: (1, Alice, 30), (2, Bob, 25), (3, Charlie, 35)
- When: `tableService.delete("users", "age > 28 AND name='Alice'")`
- Then: deletedCount == 1, Alice만 삭제됨, Bob과 Charlie 유지

**테스트 2: OR 조건 DELETE**
- Given: users 테이블 (id INT, name VARCHAR), 행: (1, Alice), (2, Bob), (3, Charlie)
- When: `tableService.delete("users", "id=1 OR id=3")`
- Then: deletedCount == 2, Bob만 유지

**테스트 3: 비교 연산자 > DELETE**
- Given: users 테이블, 행: id=1, 2, 3
- When: `tableService.delete("users", "id > 2")`
- Then: deletedCount == 1 (id=3만 삭제)

**테스트 4: 비교 연산자 < DELETE**
- Given: 동일 테이블
- When: `tableService.delete("users", "id < 2")`
- Then: deletedCount == 1 (id=1만 삭제)

**테스트 5: 비교 연산자 >= DELETE**
- Given: 동일 테이블
- When: `tableService.delete("users", "id >= 2")`
- Then: deletedCount == 2 (id=2, 3 삭제)

**테스트 6: 비교 연산자 <= DELETE**
- Given: 동일 테이블
- When: `tableService.delete("users", "id <= 2")`
- Then: deletedCount == 2 (id=1, 2 삭제)

**테스트 7: 비교 연산자 != DELETE**
- Given: 동일 테이블
- When: `tableService.delete("users", "name != 'Alice'")`
- Then: deletedCount == 2 (Bob, Charlie 삭제)

**테스트 8: INT 타입 숫자 비교 DELETE**
- Given: users 테이블 (id INT, name VARCHAR, age INT), 행: (1, Alice, 3), (2, Bob, 25), (3, Charlie, 100)
- When: `tableService.delete("users", "age > 25")`
- Then: deletedCount == 1 (Charlie만 삭제). 문자열 비교면 "3" > "25"가 true이므로 Alice도 삭제됨. INT 비교이므로 3 < 25로 Alice는 유지됨

**테스트 9: VARCHAR 타입 문자열 비교 DELETE**
- Given: users 테이블 (id INT, name VARCHAR), 행: (1, Alice), (2, Bob), (3, Charlie)
- When: `tableService.delete("users", "name > 'B'")`
- Then: deletedCount == 2 (Bob, Charlie 삭제 - 사전순으로 "Bob" > "B", "Charlie" > "B")

**테스트 10: 존재하지 않는 컬럼 WHERE 조건 시 예외**
- Given: users 테이블 (id INT, name VARCHAR)
- When: `tableService.delete("users", "email='test'")`
- Then: `ColumnNotFoundException` 발생

**테스트 11: AND 조건 매칭 없을 때 0 반환**
- Given: users 테이블, 행: (1, Alice), (2, Bob)
- When: `tableService.delete("users", "id=1 AND name='Bob'")` (id=1이면서 name=Bob인 행 없음)
- Then: deletedCount == 0

---

### Task 4-3: DELETE + VACUUM 통합 테스트

**테스트 파일**: 신규 `DeleteVacuumIntegrationTest.kt`

위치: `db-server/src/test/kotlin/study/db/server/service/DeleteVacuumIntegrationTest.kt`

이유: TableService.delete() -> TableService.vacuum() 전체 흐름을 테스트하므로, TableService + TableFileManager + VacuumService 세 컴포넌트를 모두 조합하는 별도 파일이 적합.

| # | 테스트명 | 우선순위 | 설명 |
|---|---------|---------|------|
| 1 | `DELETE 후 VACUUM 시 tombstone 행이 물리적으로 제거됨` | **Critical** | DELETE -> getTableStatistics(deleted>0) -> VACUUM -> getTableStatistics(deleted==0) |
| 2 | `VACUUM 후 파일 크기 감소` | **High** | DELETE 전 파일 크기 > VACUUM 후 파일 크기 |
| 3 | `VACUUM 후 SELECT 결과 동일` | **Critical** | DELETE 후 SELECT 결과와 VACUUM 후 SELECT 결과가 동일 |
| 4 | `DELETE 전체 행 후 VACUUM 시 빈 테이블` | **Medium** | 전체 DELETE -> VACUUM -> activeRows == 0 |
| 5 | `대량 DELETE 후 VACUUM 통합 시나리오` | **Medium** | 1000행 중 500행 DELETE -> VACUUM -> 500행만 존재 |
| 6 | `DELETE + INSERT + VACUUM 혼합 시나리오` | **Medium** | DELETE -> INSERT -> VACUUM -> 모든 데이터 정합성 검증 |

**상세 Given/When/Then:**

**테스트 1: DELETE 후 VACUUM 시 tombstone 행이 물리적으로 제거됨**
- Given: users 테이블에 5개 행 삽입, `delete("users", "id=1 OR id=3")` 실행
- When:
  - 중간 검증: `tableFileManager.getTableStatistics("users")`에서 deletedRows == 2
  - `tableService.vacuum("users")` 실행
- Then: `tableFileManager.getTableStatistics("users")`에서 totalRows == 3, deletedRows == 0, activeRows == 3

**테스트 2: VACUUM 후 파일 크기 감소**
- Given: users 테이블에 20개 행 삽입, `delete("users", "id > 10")` 실행 (10행 삭제)
- When: 파일 크기 기록 후 `tableService.vacuum("users")` 실행
- Then: VACUUM 후 파일 크기 < 이전 파일 크기, `VacuumStats.diskSpaceSaved > 0`

**테스트 3: VACUUM 후 SELECT 결과 동일**
- Given: users 테이블에 5개 행 삽입, `delete("users", "id=3")` 실행
- When: DELETE 후 SELECT 결과 기록, `vacuum("users")` 실행, VACUUM 후 SELECT 실행
- Then: DELETE 후 결과 == VACUUM 후 결과 (동일한 행, 동일한 데이터)

**테스트 4: DELETE 전체 행 후 VACUUM**
- Given: users 테이블에 5개 행 삽입
- When: `delete("users", null)` (전체 삭제) -> `vacuum("users")` 실행
- Then: `select("users")`의 rows.size == 0, getTableStatistics의 totalRows == 0

**테스트 5: 대량 DELETE 후 VACUUM**
- Given: large_table에 1000행 삽입
- When: `delete("large_table", "id > 500")` (500행 삭제) -> `vacuum("large_table")` 실행
- Then: `select("large_table")`의 rows.size == 500, 파일 크기 약 50% 감소

**테스트 6: DELETE + INSERT + VACUUM 혼합**
- Given: users 테이블에 (1, Alice), (2, Bob), (3, Charlie) 삽입
- When:
  1. `delete("users", "id=2")` (Bob 삭제)
  2. `insert("users", mapOf("id" to "4", "name" to "Dave"))` (Dave 추가)
  3. `vacuum("users")` 실행
- Then: `select("users")`에 Alice, Charlie, Dave 존재 (3행), Bob 없음

---

## 3. 테스트 파일 배치 전략

| Task | 대상 파일 | 방식 | 이유 |
|------|----------|------|------|
| 4-1 | `TableServicePersistenceTest.kt` (기존) | `@Nested` inner class 추가 | 기존 파일이 TableService + TableFileManager 통합 패턴을 갖고 있음. setUp/tearDown 재사용 가능 |
| 4-2 | `TableServiceTest.kt` (기존) | 기존 `DeleteTest` 내에 추가 또는 `ComplexWhereDeleteTest` `@Nested` 추가 | 메모리 기반 유닛 테스트이므로 기존 파일에 추가가 자연스러움 |
| 4-3 | `DeleteVacuumIntegrationTest.kt` (신규) | 신규 파일 생성 | TableService + TableFileManager + VacuumService 세 컴포넌트 조합이므로 별도 파일이 적합 |

신규 파일 위치: `db-server/src/test/kotlin/study/db/server/service/DeleteVacuumIntegrationTest.kt`

---

## 4. 테스트 우선순위 정리

### Critical (반드시 먼저 구현)
1. Task 4-1 #1: DELETE 후 SELECT 시 삭제된 행이 보이지 않음 (파일 기반)
2. Task 4-1 #2: DELETE 후 파일에서 직접 읽어도 삭제된 행이 보이지 않음
3. Task 4-3 #1: DELETE 후 VACUUM 시 tombstone 물리적 제거
4. Task 4-3 #3: VACUUM 후 SELECT 결과 동일

### High
5. Task 4-1 #3: DELETE 후 tombstone 행 존재 확인
6. Task 4-1 #4: DELETE 후 재INSERT 정상 동작
7. Task 4-1 #5: DELETE 후 서버 재시작 시 삭제 상태 유지
8. Task 4-2 #1: AND 조건 DELETE
9. Task 4-2 #3: 비교 연산자 > DELETE
10. Task 4-2 #4: 비교 연산자 < DELETE
11. Task 4-2 #8: INT 타입 숫자 비교 DELETE
12. Task 4-2 #10: 존재하지 않는 컬럼 WHERE 예외
13. Task 4-3 #2: VACUUM 후 파일 크기 감소

### Medium
14. Task 4-1 #6: 전체 행 DELETE 후 모든 행이 tombstone
15. Task 4-1 #7: 빈 테이블 DELETE
16. Task 4-1 #8: 이미 삭제된 행 재삭제
17. Task 4-2 #2: OR 조건 DELETE
18. Task 4-2 #5: >= 연산자
19. Task 4-2 #6: <= 연산자
20. Task 4-2 #7: != 연산자
21. Task 4-2 #9: VARCHAR 문자열 비교
22. Task 4-3 #4: 전체 DELETE + VACUUM
23. Task 4-3 #5: 대량 DELETE + VACUUM
24. Task 4-3 #6: DELETE + INSERT + VACUUM 혼합

### Low
25. Task 4-2 #11: AND 조건 매칭 없을 때 0 반환

---

## 5. 구현 가이드 (기존 패턴 준수)

### 5.1 Task 4-1: Persistence 테스트 패턴

기존 `TableServicePersistenceTest`의 패턴을 따른다:
- `createTempDir()` + `@AfterEach deleteRecursively()` 사용
- `TableService(tableFileManager)` 생성자로 파일 기반 서비스 생성
- `tableFileManager.readTable()` 또는 `tableService.select()`로 결과 검증
- tombstone 검증은 `tableFileManager.getTableStatistics()` 활용 (public 메서드)

### 5.2 Task 4-2: 유닛 테스트 패턴

기존 `TableServiceTest.DeleteTest`의 패턴을 따른다:
- `TableService()` (인자 없이) 생성하여 메모리 기반 테스트
- `@BeforeEach`에서 테이블 생성
- 별도 `@Nested` 클래스에서 age INT 등 추가 컬럼 포함한 스키마 정의
- WHERE 문자열은 `WhereClause.parse()` 형식 그대로 전달: `"age > 25 AND name='Alice'"`

### 5.3 Task 4-3: 통합 테스트 패턴

`VacuumIntegrationTest`의 패턴을 참고:
- `@TempDir` 사용 (JUnit5 방식)
- `TableService` + `TableFileManager` + `VacuumService` + `VacuumLockManager` + `VacuumConfig` 셋업
- `tableService.setVacuumService(vacuumService)` 호출하여 circular dependency 해결
- `tableService.vacuum(tableName)` 메서드를 통해 VACUUM 실행
- 파일 크기 비교: `File(tempDir, "$tableName.dat").length()` 사용

### 5.4 주의사항

1. **`getTableStatistics()`는 public 메서드** - tombstone 검증에 직접 사용 가능, reflection 불필요
2. **`deleteRows()`는 WhereClause 객체를 받음** - TableService.delete()가 내부에서 `WhereClause.parse()`를 호출하므로, 테스트에서는 문자열 WHERE만 전달
3. **VacuumConfig 설정** - 테스트에서 `minDeletedRows`를 1 이하로 설정해야 소량 데이터에서도 VACUUM 동작 (기본값이 높으면 shouldVacuum이 false)
4. **`tableService.vacuum()` 사용** - vacuumService.vacuumTable()보다 메모리 테이블 업데이트까지 포함하므로 더 완전한 통합 테스트
