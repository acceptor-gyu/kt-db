# Phase 2: DML 확장 — UPDATE & INSERT 다중 행 구현 계획

## 1. 현재 코드 분석

### 1.1 단일 INSERT 구현 방식

**파싱 레이어 (`ConnectionHandler.parseAndHandleInsert`)**
- 정규식 `INSERT\s+INTO\s+(\w+)\s+VALUES\s*\(([^)]+)\)` 으로 파싱
- 단일 VALUES 절 하나만 처리
- 컬럼=값 형태(`id="1", name="John"`)를 key-value 맵으로 추출

**서비스 레이어 (`TableService.insert`)**
- `tables.compute(tableName)` 을 통해 atomic하게 row 추가
- 파일 저장: `tableFileManager?.writeTable(it)` — 테이블 전체를 temp → sync → rename 방식으로 재작성
- 검증: `Resolver.validateInsertData` → `TypeValidator.validate`

**스토리지 레이어 (`RowEncoder`)**
- Row 구조: `[4byte rowLength][1byte deleted][8byte version][필드 데이터...]`
- `Row.version = 0` 으로 초기화, `Row.deleted = false`
- `Row.update()` 메서드가 이미 `data + updates`, `version + 1` 을 반환하도록 구현되어 있음

### 1.2 DELETE 구현 방식

**`TableFileManager.deleteRows`**
- `readTableWithRows` (deleted 포함 전체 읽기) → WHERE 조건 매칭 → `row.markAsDeleted()` → `writeTableWithRows` (deleted 플래그 포함 재작성)
- Tombstone 방식 (논리적 삭제)
- Vacuum으로 물리적 제거

**`WhereClause.parse` / `WhereEvaluator`**
- common 모듈에 WHERE 조건 파싱 및 평가 로직이 이미 구현되어 있음

### 1.3 Row 모델의 UPDATE 준비 상태

`Row.kt` 에 `update(updates: Map<String, String>): Row` 가 이미 구현되어 있음. UPDATE를 염두에 두고 사전 설계된 핵심 빌딩 블록이다.

---

## 2. 구현 전략

### 2.1 UPDATE 구현 전략

MySQL InnoDB의 in-place update 방식을 단순화하여 구현한다. MVCC의 Undo Log/Redo Log는 Phase 8에서 구현하고, Phase 2에서는 **in-place 업데이트 + version 증가** 방식을 채택한다.

**UPDATE 흐름:**
```
SQL 파싱 (ConnectionHandler)
  → 검증 (Resolver: SET 컬럼/타입, WHERE 컬럼)
  → 업데이트 (TableService.update)
    → TableFileManager.updateRows (Row 객체 단위로 처리)
      → WHERE 조건 매칭 → row.update(setValues)
      → writeTableWithRows (버전 증가된 row 포함 재작성)
    → BufferPool 무효화
```

**핵심 설계 결정:**
- UPDATE는 DELETE와 동일하게 `readTableWithRows` → 조건 매칭 → 수정 → `writeTableWithRows` 패턴을 따름
- `row.update()` 메서드가 이미 버전을 증가시키므로 MVCC의 기초 준비
- WHERE 절이 없으면 전체 업데이트 (DELETE와 동일한 패턴)

### 2.2 INSERT 다중 행 구현 전략

여러 행을 한 번의 파일 쓰기로 처리하여 I/O 최소화.

**다중 INSERT 흐름:**
```
SQL 파싱 (ConnectionHandler) — 여러 VALUES 절 추출
  → 검증 (Resolver: 각 row의 컬럼/타입)
  → 삽입 (TableService.insertBatch)
    → tables.compute: 기존 rows + 새 rows 일괄 추가
    → 파일에 한 번만 쓰기 (writeTable 한 번 호출)
```

**핵심 설계 결정:**
- 기존 `insert` 시그니처 유지 (하위 호환)
- `insertBatch(tableName, List<Map<String, String>>)` 신규 메서드 추가
- 모든 행 검증을 한 번에 수행 후 atomic compute로 일괄 삽입
- 단일 `writeTable` 호출로 I/O를 최소화

---

## 3. 수정/추가할 파일 목록

### 3.1 UPDATE 구현

#### [수정] `ConnectionHandler.kt`
- `processRequest` 분기에 `UPDATE` 케이스 추가
- `parseAndHandleUpdate(sql: String)` 메서드 신규 추가
- 파싱 로직: `UPDATE\s+(\w+)\s+SET\s+(.+?)(?:\s+WHERE\s+(.+))?$` 정규식
- SET 절 파싱: `col=val` 쌍을 Map으로 추출 (기존 INSERT의 `valueRegex` 패턴 재사용)

#### [수정] `TableService.kt`
- `update(tableName, setValues, whereString)` 메서드 추가
- 검증 → atomic update → 파일 저장 플로우
- 테이블 단위 `ReentrantLock` 적용 (TOCTOU 문제 해결)

#### [수정] `TableFileManager.kt`
- `updateRows(tableName, schema, setValues, whereClause): Int` 메서드 추가
- `readTableWithRows` → WHERE 매칭 → `row.update(setValues)` → `writeTableWithRows` 패턴
- 반환값: 업데이트된 행 수

#### [수정] `Resolver.kt`
- `validateUpdateData(table, setValues)` 메서드 추가
  - SET 컬럼이 테이블에 존재하는지 확인
  - 각 값의 타입 검증 (`validateColumnType` 재사용)

### 3.2 INSERT 다중 행 구현

#### [수정] `ConnectionHandler.kt`
- 기존 `parseAndHandleInsert` 수정: 다중 VALUES 절 감지
- 단일 row면 기존 `insert`, 다중이면 `insertBatch` 호출
- 정규식을 `INSERT\s+INTO\s+(\w+)\s+VALUES\s*(.+)` 로 확장하여 전체 VALUES 문자열 추출
- VALUES 절 분리 로직: `(...)` 단위로 분리

#### [수정] `TableService.kt`
- `insertBatch(tableName, rows: List<Map<String, String>>)` 메서드 추가
- 모든 row 검증 → `tables.compute`로 일괄 추가 → 파일 한 번 쓰기

---

## 4. 구현 순서 (단계별)

### Step 1: UPDATE 파싱 및 라우팅
`ConnectionHandler.kt` 에 `parseAndHandleUpdate` 메서드 작성.

```kotlin
// 목표 SQL 형태
"UPDATE users SET name='Bob' WHERE id=1"
"UPDATE users SET age=25, status='active'"

// 정규식
val regex = Regex(
    """UPDATE\s+(\w+)\s+SET\s+(.+?)(?:\s+WHERE\s+(.+))?$""",
    RegexOption.IGNORE_CASE
)
```

### Step 2: Resolver 확장 (UPDATE 검증)
`Resolver.kt` 에 `validateUpdateData` 추가. 기존 `validateInsertData`와 구조가 동일하다.

### Step 3: TableFileManager.updateRows 구현
DELETE의 `deleteRows` 구현을 거울처럼 따른다. 차이점은 `markAsDeleted()` 대신 `row.update(setValues)` 호출.

```kotlin
fun updateRows(tableName: String, schema: Map<String, String>,
               setValues: Map<String, String>, whereClause: WhereClause): Int {
    val (_, allRows) = readTableWithRows(tableName) ?: return 0
    var updatedCount = 0
    val newRows = allRows.map { row ->
        if (!row.deleted && WhereEvaluator.matches(row, whereClause, schema)) {
            updatedCount++
            row.update(setValues)  // version +1, data 업데이트
        } else {
            row
        }
    }
    if (updatedCount > 0) writeTableWithRows(tableName, schema, newRows)
    return updatedCount
}
```

### Step 4: TableService.update 구현
DELETE 패턴을 거의 그대로 따른다.

### Step 5: INSERT 다중 행 파싱
`ConnectionHandler.parseAndHandleInsert` 에서 VALUES 절을 여러 개 추출하는 로직 추가.

```
"INSERT INTO users VALUES (id=1, name='A'), (id=2, name='B')"
                         ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                         → [{ id:1, name:A }, { id:2, name:B }]
```

괄호 단위로 분리: 상태 기반 토크나이저로 각 VALUES 그룹 추출 (값에 `,`가 포함될 수 있으므로 정규식 대신 권장).

### Step 6: TableService.insertBatch 구현
단일 `compute` 블록에서 여러 행을 한번에 추가.

```kotlin
fun insertBatch(tableName: String, rows: List<Map<String, String>>) {
    val existingTable = tables[tableName]
        ?: throw IllegalStateException("Table '$tableName' not found")
    rows.forEach { validateInsertData(existingTable, it) }  // 전체 검증 먼저
    val updated = tables.compute(tableName) { _, table ->
        table?.copy(rows = table.rows + rows)
    }
    updated?.let { tableFileManager?.writeTable(it) }
}
```

### Step 7: 테스트 작성

1. `TableServiceTest.kt` — UPDATE/insertBatch 단위 테스트 (인메모리)
2. `TableServicePersistenceTest.kt` — 파일 저장/복원 검증
3. `ConnectionHandlerTest.kt` — SQL 파싱 통합 테스트

---

## 5. 고려사항

### 5.1 스레드 안전성

**UPDATE의 TOCTOU 문제**

`readTableWithRows → modify → writeTableWithRows` 패턴은 두 스레드가 동시에 같은 테이블을 UPDATE할 경우 한 쪽의 변경이 덮어씌워질 수 있다.

**해결 방안**: `TableService`에 테이블 단위 `ReentrantLock` 적용.

```kotlin
private val tableLocks = ConcurrentHashMap<String, ReentrantLock>()

private fun <T> withTableLock(tableName: String, block: () -> T): T {
    val lock = tableLocks.computeIfAbsent(tableName) { ReentrantLock() }
    return lock.withLock(block)
}
```

DELETE도 동일한 문제가 있으므로 이번 Phase에서 함께 개선을 권장한다.

**INSERT 다중 행**

`insertBatch`는 단일 `compute` 블록 내에서 모든 행을 추가하므로 원자적으로 스레드 안전하다.

### 5.2 파일 I/O 처리

현재 모든 쓰기는 파일 전체를 재작성한다. 대용량 테이블에서 빈번한 UPDATE는 성능 저하가 있을 수 있으나, Phase 2 범위 내에서 허용 가능한 단순화이며 Phase 5(인덱스)나 Phase 8(WAL) 단계에서 개선한다.

`insertBatch`는 `writeTable` 호출을 한 번으로 줄여 N개 단일 INSERT 대비 파일 쓰기 횟수를 N배 감소시킨다.

### 5.3 에지 케이스

**UPDATE 에지 케이스:**
- WHERE 조건에 매칭되는 행이 없을 때: `updatedCount = 0` 반환, 성공 응답 (`"0 row(s) updated"`)
- SET에 스키마에 없는 컬럼 지정: `ColumnNotFoundException` 발생
- SET 값의 타입 불일치: `TypeMismatchException` 발생
- WHERE 절 없이 UPDATE: 전체 행 업데이트 (명시적으로 허용)

**INSERT 다중 행 에지 케이스:**
- 일부 행만 검증 실패 시 원자성 보장: **모든 행 검증을 compute 전에 완료** 하여 해결
- VALUES 절 파싱에서 값에 `,` 또는 `()`가 포함된 VARCHAR 처리: 따옴표 내 괄호/콤마는 상태 기반 파서(토크나이저)로 처리

### 5.4 BufferPool 무효화

- UPDATE: `writeTableWithRows` 내부에서 `bufferPool?.invalidateTable(tableName)` 이 이미 호출되므로 자동 처리
- insertBatch: `writeTable` 사용 시 무효화 호출 여부 확인 필요

### 5.5 응답 메시지 형식

기존 패턴을 따라:
- UPDATE 성공: `"Updated N row(s)"`
- INSERT 다중 행 성공: `"Data inserted (N rows)"`

---

## 6. 구현 파일 요약

| 파일 | 변경 유형 | 주요 변경 내용 |
|------|-----------|---------------|
| `ConnectionHandler.kt` | 수정 | `parseAndHandleUpdate` 추가, `parseAndHandleInsert` 다중 VALUES 처리 |
| `TableService.kt` | 수정 | `update()`, `insertBatch()` 메서드 추가, 테이블 단위 잠금 강화 |
| `TableFileManager.kt` | 수정 | `updateRows()` 메서드 추가 |
| `Resolver.kt` | 수정 | `validateUpdateData()` 추가 |
| `TableServiceTest.kt` | 수정 | UPDATE/insertBatch 단위 테스트 추가 |
| `TableServicePersistenceTest.kt` | 수정 | UPDATE/insertBatch 퍼시스턴스 테스트 추가 |

---

## 7. 핵심 파일 경로

- `db-server/src/main/kotlin/study/db/server/db_engine/ConnectionHandler.kt`
- `db-server/src/main/kotlin/study/db/server/service/TableService.kt`
- `db-server/src/main/kotlin/study/db/server/storage/TableFileManager.kt`
- `db-server/src/main/kotlin/study/db/server/db_engine/Resolver.kt`
- `common/src/main/kotlin/study/db/common/Row.kt` (이미 `update()` 구현 완료 — 변경 불필요)
