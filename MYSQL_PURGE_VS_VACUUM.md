# MySQL Purge vs 현재 VACUUM 구현: 심층 비교

## 목차
1. [MySQL InnoDB Purge Thread 동작 원리](#1-mysql-innodb-purge-thread-동작-원리)
2. [현재 프로젝트의 VACUUM 동작 원리](#2-현재-프로젝트의-vacuum-동작-원리)
3. [핵심 차이점 9가지](#3-핵심-차이점-9가지)
4. [성능 비교](#4-성능-비교)
5. [Trade-offs 분석](#5-trade-offs-분석)

---

## 1. MySQL InnoDB Purge Thread 동작 원리

### 1.1 InnoDB의 데이터 구조

InnoDB는 각 행에 **히든 컬럼**을 저장합니다:

```sql
-- 사용자가 보는 테이블
CREATE TABLE users (
    id INT PRIMARY KEY,
    name VARCHAR(100)
);

-- InnoDB가 실제로 저장하는 구조
[DB_ROW_ID | DB_TRX_ID | DB_ROLL_PTR | id | name]
    (6)        (6)          (7)         (4)  (100)
```

**히든 컬럼 설명:**
- `DB_ROW_ID` (6 bytes): 행 고유 ID (PK 없을 때만)
- `DB_TRX_ID` (6 bytes): 이 행을 마지막으로 수정한 트랜잭션 ID
- `DB_ROLL_PTR` (7 bytes): Undo Log 포인터 (이전 버전 위치)

### 1.2 DELETE 실행 시 (In-Place Update)

```sql
-- T1: Transaction 1 시작
START TRANSACTION;
DELETE FROM users WHERE id = 1;
```

**InnoDB 내부 동작:**

```c
// 1. 행에 delete mark 설정 (In-Place)
row->deleted_flag = 1;
row->trx_id = 1005;  // 현재 트랜잭션 ID

// 2. Undo Log 생성 (이전 버전 저장)
undo_log->trx_id = 1001;
undo_log->operation = TRX_UNDO_DEL_MARK_REC;
undo_log->old_row = {id: 1, name: "John", deleted_flag: 0};

// 3. 파일은 그대로, 메모리만 변경
// 실제 디스크 쓰기는 나중에 (Buffer Pool flush)
```

**핵심:** 파일을 다시 쓰지 않고, **기존 행의 플래그만 변경** (In-Place Update)

### 1.3 Purge Thread의 동작

```c
// InnoDB Purge Thread (의사 코드)
void purge_coordinator_thread() {
    while (true) {
        // 1. Read View 확인: 가장 오래된 활성 트랜잭션 찾기
        ReadView oldest_view = get_oldest_active_transaction();

        // 2. History List 순회
        for (undo_record in history_list) {
            if (undo_record.trx_id < oldest_view.low_limit_id) {
                // 모든 트랜잭션이 이 버전을 더 이상 필요로 하지 않음

                if (undo_record.type == TRX_UNDO_DEL_MARK_REC) {
                    // DELETE된 행 물리적 제거
                    physically_remove_row(undo_record.table_id, undo_record.row_id);
                }

                // Undo Log 제거
                free_undo_log(undo_record);
            }
        }

        // 3. 주기적 실행
        sleep(innodb_purge_batch_size);
    }
}
```

**핵심 알고리즘:**

```
DELETE (trx_id=1005)
    ↓
[Row: deleted_flag=1, trx_id=1005]  ← 아직 남아있음
    ↓
Purge Thread 주기 실행
    ↓
"trx_id=1005보다 오래된 모든 트랜잭션이 종료되었나?" 체크
    ↓
YES → 물리적 제거 (B-Tree에서 삭제)
NO  → 다음 주기까지 대기
```

### 1.4 InnoDB의 MVCC와 Purge

```sql
-- T1: DELETE 실행
START TRANSACTION;  -- trx_id=1005
DELETE FROM users WHERE id = 1;
COMMIT;

-- T2: 아직 실행 중인 오래된 트랜잭션
START TRANSACTION;  -- trx_id=1000 (T1보다 먼저 시작)
SELECT * FROM users WHERE id = 1;  -- John이 보여야 함!
-- 이 트랜잭션이 끝나기 전까지 Purge는 대기
```

**Purge 조건:**

```c
// Purge 가능 조건
bool can_purge(undo_record) {
    // 모든 활성 트랜잭션의 최소 ID
    trx_id_t min_active_trx_id = get_min_active_trx_id();

    // 이 행을 삭제한 트랜잭션이 모든 활성 트랜잭션보다 오래되었나?
    return undo_record.trx_id < min_active_trx_id;
}
```

---

## 2. 현재 프로젝트의 VACUUM 동작 원리

### 2.1 데이터 구조

```kotlin
// 현재 프로젝트의 Row 구조
data class Row(
    val data: Map<String, String>,  // 실제 데이터
    val deleted: Boolean = false,   // 삭제 플래그
    val version: Long = 0           // 버전 (MVCC 준비용)
)
```

**차이점:** 트랜잭션 ID가 없음 (트랜잭션 미지원)

### 2.2 DELETE 실행 시 (Copy-on-Write)

```kotlin
fun deleteRows(tableName: String, columnName: String?, value: String?): Int {
    // 1. 파일에서 모든 Row 읽기 (전체 파일 로드)
    val (schema, allRows) = readTableWithRows(tableName)

    // 2. 메모리에서 deleted 플래그 설정
    val updatedRows = allRows.map { row ->
        if (shouldDelete(row)) {
            row.markAsDeleted()  // row.copy(deleted = true)
        } else {
            row
        }
    }

    // 3. 파일 전체를 다시 쓰기 (Copy-on-Write)
    writeTableWithRows(tableName, schema, updatedRows)
    // → users.dat.tmp에 쓰고 → users.dat로 atomic rename
}
```

**핵심:** 파일 전체를 읽고 → 수정하고 → 다시 쓰기 (Copy-on-Write)

### 2.3 VACUUM 실행 (Manual Copy-on-Write)

```kotlin
fun compactTable(tableName: String): VacuumStats {
    // PHASE 1: SNAPSHOT - 파일 수정 시간 기록
    val t1 = file.lastModified()

    // PHASE 2: COPY - 활성 행만 새 파일에 복사
    val (schema, allRows) = readTableWithRows(tableName)
    val activeRows = allRows.filter { !it.deleted }

    RandomAccessFile(vacuumFile, "rw").use { raf ->
        writeHeader(raf, activeRows.size)
        writeSchema(raf, schema)
        activeRows.forEach { row ->
            raf.write(rowEncoder.encodeRow(row, schema))
        }
        raf.fd.sync()  // 디스크 동기화
    }

    // PHASE 3: VERIFICATION - 동시 쓰기 감지
    val t2 = file.lastModified()
    if (t1 != t2) {
        // 다른 스레드가 INSERT/DELETE를 실행했음
        vacuumFile.delete()
        return VacuumStats.failure("Concurrent write detected", abortedDueToWrite = true)
    }

    // PHASE 4: FILE SWAP - Atomic rename
    file.renameTo(oldFile)           // users.dat → users.dat.old
    vacuumFile.renameTo(file)        // users.dat.vacuum → users.dat
    oldFile.delete()                 // users.dat.old 삭제
}
```

**핵심:** 수동 실행 + 파일 수정 시간으로 동시 쓰기 감지

---

## 3. 핵심 차이점 9가지

### 3.1 실행 방식

| 항목 | MySQL Purge | 현재 VACUUM |
|------|-------------|-------------|
| **실행 주체** | 백그라운드 스레드 (자동) | 사용자 명령 (수동) |
| **실행 시점** | 1~2초마다 자동 실행 | `VACUUM` 명령 시만 |
| **동시 실행** | 항상 백그라운드 실행 중 | 명령 실행 시만 |

**MySQL 설정:**
```sql
-- Purge Thread 개수 설정 (기본 4개)
SET GLOBAL innodb_purge_threads = 4;

-- Purge 배치 크기 (한 번에 처리할 행 수)
SET GLOBAL innodb_purge_batch_size = 300;
```

**현재 프로젝트:**
```kotlin
// 수동 실행만 가능
tableService.vacuum("users")

// 자동 실행은 VacuumScheduler로 구현 가능
@Scheduled(fixedDelay = 60000)
fun autoVacuum() {
    if (shouldVacuum("users")) {
        vacuumService.vacuumTable("users")
    }
}
```

### 3.2 동시성 제어 메커니즘

#### MySQL: MVCC + 트랜잭션 ID

```c
// Purge 가능 여부 판단
bool can_purge_row(deleted_row) {
    // 1. 가장 오래된 활성 트랜잭션 찾기
    trx_id_t oldest_trx = read_view_get_low_limit_id();

    // 2. 이 행을 삭제한 트랜잭션이 더 오래되었나?
    if (deleted_row->trx_id < oldest_trx) {
        // 모든 트랜잭션이 이 삭제를 "볼 수 있음" → Purge 가능
        return true;
    }

    // 3. 아직 이 행의 이전 버전을 필요로 하는 트랜잭션 존재
    return false;
}
```

**시나리오:**
```sql
-- T1 (trx_id=1000): 아직 실행 중
START TRANSACTION;
SELECT * FROM users WHERE id = 1;  -- John

-- T2 (trx_id=1005): DELETE 실행 후 커밋
START TRANSACTION;
DELETE FROM users WHERE id = 1;
COMMIT;

-- Purge Thread: T1이 끝나기 전까지는 John을 삭제하지 않음
-- T1이 COMMIT되면 → Purge 실행
```

#### 현재 프로젝트: 파일 수정 시간 비교

```kotlin
// 동시 쓰기 감지
val t1 = file.lastModified()  // 1706345678000
// ... VACUUM 작업 수행 ...
val t2 = file.lastModified()  // 1706345678000

if (t1 != t2) {
    // 다른 스레드가 파일을 수정함 → ABORT
    throw ConcurrentModificationException()
}
```

**시나리오:**
```kotlin
// Thread 1: VACUUM 실행 중
val t1 = file.lastModified()  // 1000
// ... activeRows 복사 중 ...

// Thread 2: INSERT 실행
tableService.insert("users", mapOf("id" to "999"))
// → file.setLastModified(2000)

// Thread 1: VACUUM 검증
val t2 = file.lastModified()  // 2000
if (t1 != t2) {  // 1000 != 2000
    abort()  // VACUUM 취소 후 재시도
}
```

### 3.3 데이터 일관성 보장 방법

#### MySQL: Read View (Snapshot Isolation)

```c
// 트랜잭션 시작 시 Read View 생성
struct ReadView {
    trx_id_t low_limit_id;   // 아직 시작하지 않은 트랜잭션의 최소 ID
    trx_id_t up_limit_id;    // 활성 트랜잭션의 최소 ID
    trx_id_t* trx_ids;       // 활성 트랜잭션 ID 배열
};

// SELECT 실행 시 행 가시성 판단
bool is_visible(row, read_view) {
    if (row->trx_id < read_view->up_limit_id) {
        return true;  // 이 트랜잭션 시작 전에 커밋됨
    }

    if (row->trx_id >= read_view->low_limit_id) {
        return false;  // 이 트랜잭션 시작 후에 생성됨
    }

    // 활성 트랜잭션 중 하나가 수정함 → Undo Log에서 이전 버전 찾기
    return search_undo_log(row, read_view);
}
```

#### 현재 프로젝트: File Modification Time

```kotlin
// 단순히 파일이 수정되었는지만 확인
fun detectConcurrentWrite(initialTime: Long, currentTime: Long): Boolean {
    return initialTime != currentTime
}
```

**한계:**
- 트랜잭션 개념 없음
- 어떤 스레드가 수정했는지 모름
- 이전 버전 복구 불가

### 3.4 삭제 방식

#### MySQL: In-Place Update (제자리 수정)

```
Before DELETE:
Page 100: [Row1: id=1, trx_id=1000, deleted_flag=0]
          [Row2: id=2, trx_id=1001, deleted_flag=0]

After DELETE (id=1):
Page 100: [Row1: id=1, trx_id=1005, deleted_flag=1]  ← 같은 위치
          [Row2: id=2, trx_id=1001, deleted_flag=0]

After Purge:
Page 100: [Row2: id=2, trx_id=1001, deleted_flag=0]
          [빈 공간 - Free List에 추가]
```

**특징:**
- 파일 전체를 다시 쓰지 않음
- B-Tree 구조에서 노드만 제거
- Free Space는 Page 내부에서 재사용

#### 현재 프로젝트: Copy-on-Write

```
Before DELETE:
users.dat (1024KB):
  [Row1: {id=1, deleted=false}]
  [Row2: {id=2, deleted=false}]

After DELETE (id=1):
users.dat (1024KB):  ← 크기 그대로
  [Row1: {id=1, deleted=true}]   ← 플래그만 변경
  [Row2: {id=2, deleted=false}]

After VACUUM:
users.dat.vacuum (512KB):  ← 새 파일 생성
  [Row2: {id=2, deleted=false}]

Atomic Swap:
users.dat → users.dat.old (삭제)
users.dat.vacuum → users.dat
```

**특징:**
- 파일 전체를 복사
- 임시로 2배의 디스크 공간 필요
- Page 구조 재구성

### 3.5 성능 특성

#### MySQL Purge

```c
// Purge는 매우 가벼운 작업
void purge_one_record() {
    // 1. B-Tree에서 노드 제거 (O(log N))
    btr_remove_node(row_id);

    // 2. Undo Log 제거 (O(1))
    free_undo_log(undo_ptr);

    // 3. Free Space List 업데이트 (O(1))
    add_to_free_list(page, offset);
}

// 한 번에 300개 처리 (innodb_purge_batch_size)
// 총 소요 시간: ~1ms
```

**특징:**
- 한 행 삭제: ~0.003ms (매우 빠름)
- I/O 최소화 (Buffer Pool 활용)
- 사용자 쿼리에 영향 거의 없음

#### 현재 VACUUM

```kotlin
// VACUUM은 무거운 작업
fun compactTable(tableName: String): VacuumStats {
    // 1. 파일 전체 읽기 (O(N))
    val allRows = readTableWithRows(tableName)  // 100MB 읽기 → ~200ms

    // 2. 필터링 (O(N))
    val activeRows = allRows.filter { !it.deleted }  // ~50ms

    // 3. 파일 전체 쓰기 (O(N))
    writeTableWithRows(tableName, activeRows)  // 80MB 쓰기 → ~300ms

    // 4. File Sync (디스크 동기화)
    raf.fd.sync()  // ~50ms

    // 총 소요 시간: ~600ms (1000배 느림)
}
```

**특징:**
- 테이블 크기에 비례 (100MB → 600ms)
- 디스크 I/O 집약적
- Write Lock 필요 (SELECT도 블록될 수 있음)

### 3.6 재시도 메커니즘

#### MySQL Purge

```c
// Purge는 History List를 큐처럼 사용
struct trx_sys_t {
    UT_LIST_BASE_NODE_T(trx_undo_t) rseg_history;  // Purge 대기 큐
};

void purge_coordinator_thread() {
    while (true) {
        // 1. History List에서 Purge 대상 가져오기
        trx_undo_t* undo = UT_LIST_GET_FIRST(rseg_history);

        // 2. Purge 가능한지 확인
        if (can_purge(undo)) {
            purge_and_remove_from_list(undo);
        } else {
            // 다음 주기에 다시 시도
            break;
        }
    }
}
```

**특징:**
- 큐 기반 순차 처리
- 실패 시 다음 주기에 자동 재시도
- 재시도 지연 없음 (1~2초 주기)

#### 현재 VACUUM

```kotlin
fun vacuumTable(tableName: String): VacuumStats {
    var retryCount = 0

    while (retryCount <= maxRetries) {  // 최대 5회
        val result = vacuumTableOnce(tableName)

        if (result.success) {
            return result
        }

        if (!result.abortedDueToWrite) {
            return result  // 쓰기 충돌 외 오류는 재시도 안 함
        }

        retryCount++

        // Exponential Backoff
        val delay = min(1000 * 2^(retryCount-1), 60000)
        Thread.sleep(delay)  // 1초 → 2초 → 4초 → 8초 → 16초
    }

    return VacuumStats.failure("Failed after 5 retries")
}
```

**특징:**
- 명시적 재시도 로직
- Exponential Backoff (지수 백오프)
- 최대 5회 시도 후 포기

### 3.7 디스크 공간 요구사항

#### MySQL Purge

```
[Page 100: 16KB]
  Before: [Row1 | Row2 | Row3 | Row4]
  After:  [Row2 | Row4 | FREE | FREE]
           ↑ Free Space는 같은 페이지 내에서 재사용
```

**디스크 공간:**
- 추가 공간 불필요 (In-Place Update)
- Free Space는 Page 내부에서 관리
- Tablespace는 자동으로 축소되지 않음 (수동: `OPTIMIZE TABLE`)

#### 현재 VACUUM

```
Before VACUUM:
  users.dat: 1000MB
  Free space: 500MB

During VACUUM:
  users.dat: 1000MB
  users.dat.vacuum: 700MB  ← 임시 파일
  Required free space: 700MB

After VACUUM:
  users.dat: 700MB
  Free space: 800MB
```

**디스크 공간:**
- 임시로 파일 크기만큼 추가 공간 필요
- VACUUM 전 디스크 공간 체크 필수:

```kotlin
fun hasSufficientDiskSpace(tableName: String): Boolean {
    val tableSize = getTableStatistics(tableName).fileSizeBytes
    val freeSpace = dataDirectory.usableSpace
    val requiredSpace = (tableSize * 1.5).toLong()  // 50% 여유 필요

    return freeSpace >= requiredSpace
}
```

### 3.8 I/O 패턴

#### MySQL Purge

```c
// I/O 패턴: Random Write (B-Tree 구조)
void purge_row(row_id) {
    // 1. B-Tree에서 해당 페이지 로드 (Buffer Pool 캐시)
    page = load_page(table_id, page_no);  // 16KB 읽기

    // 2. 페이지 내에서 행만 제거
    remove_row_from_page(page, row_id);

    // 3. 페이지만 디스크에 쓰기 (Dirty Page)
    mark_page_dirty(page);  // 나중에 Checkpoint에서 flush
}

// Total I/O: 16KB read + 16KB write (행 크기 무관)
```

#### 현재 VACUUM

```kotlin
// I/O 패턴: Sequential Read/Write (전체 파일)
fun compactTable(tableName: String) {
    // 1. 파일 전체를 순차 읽기
    val allRows = readTableWithRows(tableName)
    // → 1000MB 파일이면 1000MB 전부 읽기

    // 2. 새 파일에 순차 쓰기
    writeTableWithRows(vacuumFile, activeRows)
    // → 700MB 쓰기

    // Total I/O: 1000MB read + 700MB write
}
```

**비교:**
```
MySQL Purge:
  100만 행 삭제 → 16KB × 100만 = ~16GB I/O
  하지만 Buffer Pool 캐싱으로 실제 I/O는 훨씬 적음

현재 VACUUM:
  1000MB 테이블 → 1000MB read + 700MB write = 1700MB I/O
  캐싱 불가 (전체 파일 복사)
```

### 3.9 Undo Log vs 파일 복사

#### MySQL: Undo Log

```c
// Undo Log 구조
struct trx_undo_rec_t {
    undo_no_t undo_no;        // Undo 번호
    table_id_t table_id;      // 테이블 ID
    byte type;                // INSERT/UPDATE/DELETE
    byte* old_row_data;       // 이전 버전 데이터
    trx_id_t trx_id;          // 트랜잭션 ID
};

// DELETE 시 Undo Log 생성
void row_undo_delete(row) {
    undo_rec = create_undo_log(
        type = TRX_UNDO_DEL_MARK_REC,
        old_data = {id: 1, name: "John", deleted: 0},
        trx_id = current_trx_id
    );

    // Rollback 시 복구 가능
    append_to_undo_log(undo_rec);
}
```

**특징:**
- 트랜잭션 롤백 가능
- MVCC로 이전 버전 읽기 가능
- Undo Log는 별도 Tablespace에 저장

#### 현재 프로젝트: 파일 복사 (이전 버전 없음)

```kotlin
// DELETE 후 이전 버전 복구 불가
fun deleteRows(tableName: String, columnName: String, value: String) {
    val updatedRows = allRows.map { row ->
        if (row.data[columnName] == value) {
            row.markAsDeleted()  // 이전 버전은 사라짐
        } else {
            row
        }
    }

    writeTableWithRows(tableName, updatedRows)
    // → 이전 파일은 덮어씌워짐 (복구 불가)
}
```

**한계:**
- 트랜잭션 롤백 불가
- MVCC 불가 (이전 버전 접근 불가)
- 실수로 삭제하면 복구 어려움

---

## 4. 성능 비교

### 4.1 Purge vs VACUUM 시간

**테스트 환경:**
- 테이블 크기: 100MB (100만 행)
- 삭제된 행: 30만 행 (30%)
- SSD: 500MB/s read, 400MB/s write

#### MySQL Purge

```sql
-- 30만 행 삭제
DELETE FROM users WHERE status = 'inactive';  -- 30만 행

-- Purge는 백그라운드에서 자동 실행
-- 사용자는 기다릴 필요 없음
```

**Purge 소요 시간:**
```
300,000 rows × 0.003ms/row = 900ms (백그라운드)
```

**사용자 입장:**
- DELETE 명령: ~500ms (인덱스 업데이트 포함)
- Purge 대기: 0ms (백그라운드)
- **총 체감 시간: 500ms**

#### 현재 VACUUM

```kotlin
// 30만 행 삭제
tableService.delete("users", "status='inactive'")  // 30만 행

// VACUUM 수동 실행
val stats = tableService.vacuum("users")
```

**VACUUM 소요 시간:**
```
Read:  100MB ÷ 500MB/s = 200ms
Write:  70MB ÷ 400MB/s = 175ms
Sync:                    50ms
Total:                  425ms (Foreground)
```

**사용자 입장:**
- DELETE 명령: ~500ms
- VACUUM 실행: ~425ms
- **총 체감 시간: 925ms (2배 느림)**

### 4.2 동시성 성능

#### MySQL Purge (높은 동시성)

```sql
-- Session 1: SELECT (읽기)
START TRANSACTION;
SELECT * FROM users;  -- Purge와 동시 실행 가능
-- Purge가 실행 중이어도 성능 저하 없음

-- Session 2: INSERT (쓰기)
INSERT INTO users VALUES (...);  -- Purge와 동시 실행 가능

-- Session 3: Purge Thread
-- 백그라운드에서 조용히 실행
```

**동시 실행 가능:**
- SELECT: ✅ (Row Lock도 필요 없음)
- INSERT: ✅ (다른 페이지면 문제 없음)
- UPDATE: ✅ (같은 행만 잠금)
- DELETE: ✅ (같은 행만 잠금)

#### 현재 VACUUM (낮은 동시성)

```kotlin
// Thread 1: VACUUM 실행
tableService.vacuum("users")
// → Write Lock 획득

// Thread 2: SELECT 시도
tableService.select("users")
// → Read Lock 대기 (VACUUM 끝날 때까지 블록)

// Thread 3: INSERT 시도
tableService.insert("users", data)
// → Read Lock 대기 (VACUUM 끝날 때까지 블록)
```

**동시 실행 불가:**
- SELECT: ❌ (VACUUM이 Write Lock 보유)
- INSERT: ❌ (VACUUM이 Write Lock 보유)
- UPDATE: ❌ (구현 안 됨)
- DELETE: ❌ (VACUUM이 Write Lock 보유)

**성능 영향:**
```
VACUUM 실행 시간: 425ms
→ 425ms 동안 모든 읽기/쓰기 차단
→ TPS (Transactions Per Second) 급감
```

### 4.3 I/O 효율성

#### MySQL Purge

```c
// Buffer Pool 활용 (캐시 히트율 99%)
void purge_with_buffer_pool() {
    page = get_page_from_buffer_pool(page_no);
    if (page == NULL) {
        page = load_page_from_disk(page_no);  // Cache Miss (1%)
        add_to_buffer_pool(page);
    }

    remove_row_from_page(page);
    mark_page_dirty(page);
    // 실제 디스크 쓰기는 나중에 (Lazy Write)
}
```

**I/O 통계:**
```
30만 행 Purge:
  Logical Read:  300,000 × 16KB = 4.8GB (메모리)
  Physical Read: 300,000 × 16KB × 0.01 = 48MB (디스크)
  Physical Write: 48MB (Checkpoint 시)
```

#### 현재 VACUUM

```kotlin
// Buffer Pool 없음 (매번 디스크 I/O)
fun compactTable(tableName: String) {
    // 파일 전체를 디스크에서 읽기
    val allRows = readTableWithRows(tableName)  // 100MB disk read

    // 파일 전체를 디스크에 쓰기
    writeTableWithRows(vacuumFile, activeRows)  // 70MB disk write
}
```

**I/O 통계:**
```
VACUUM 1회:
  Physical Read:  100MB (디스크)
  Physical Write:  70MB (디스크)
```

**비교:**
```
MySQL Purge: 48MB + 48MB = 96MB I/O (캐싱 효과)
현재 VACUUM: 100MB + 70MB = 170MB I/O (2배 많음)
```

---

## 5. Trade-offs 분석

### 5.1 MySQL Purge의 장점

✅ **자동화**
- 사용자가 신경 쓸 필요 없음
- 삭제된 행이 자동으로 정리됨

✅ **높은 동시성**
- 사용자 쿼리에 영향 거의 없음
- 백그라운드에서 조용히 실행

✅ **빠른 속도**
- In-Place Update로 I/O 최소화
- Buffer Pool 캐싱 효과

✅ **MVCC 지원**
- 트랜잭션 격리 수준 보장
- 이전 버전 읽기 가능

### 5.2 MySQL Purge의 단점

❌ **복잡한 구현**
- Undo Log, Read View, History List 등
- 수천 줄의 C 코드

❌ **메모리 오버헤드**
- Undo Log 저장 공간 (수 GB)
- Buffer Pool (기본 128MB ~ 수십 GB)

❌ **디버깅 어려움**
- 백그라운드 동작으로 문제 파악 어려움
- Purge Lag (지연) 발생 가능

### 5.3 현재 VACUUM의 장점

✅ **단순한 구현**
- 200줄 정도의 Kotlin 코드
- 이해하기 쉬움

✅ **명확한 동작**
- 수동 실행으로 예측 가능
- 언제 디스크 공간이 회수되는지 명확

✅ **트랜잭션 불필요**
- 간단한 파일 시스템만 사용
- 트랜잭션 로그 불필요

### 5.4 현재 VACUUM의 단점

❌ **수동 실행 필요**
- 사용자가 직접 `VACUUM` 명령 실행
- 자동화 어려움 (별도 스케줄러 필요)

❌ **낮은 동시성**
- VACUUM 중 모든 쿼리 차단
- 대용량 테이블에서 치명적

❌ **느린 속도**
- 파일 전체 복사로 I/O 부담
- 테이블 크기에 비례하여 지연 증가

❌ **디스크 공간 부족 위험**
- 임시로 2배의 공간 필요
- 디스크 공간 부족 시 VACUUM 실패

---

## 6. 개선 아이디어

### 6.1 Incremental VACUUM (증분 VACUUM)

현재는 파일 전체를 복사하지만, **페이지 단위로 점진적 정리** 가능:

```kotlin
// 현재: 전체 복사
fun compactTable(tableName: String) {
    val allRows = readTableWithRows(tableName)  // 100MB 전부
    val activeRows = allRows.filter { !it.deleted }
    writeTableWithRows(vacuumFile, activeRows)
}

// 개선: 페이지별 정리
fun incrementalVacuum(tableName: String, maxPages: Int = 100) {
    for (pageNo in 0 until min(getPageCount(tableName), maxPages)) {
        val page = readPage(tableName, pageNo)

        if (page.hasDeletedRows()) {
            val activeRows = page.rows.filter { !it.deleted }
            rewritePage(tableName, pageNo, activeRows)
        }
    }
}
```

**장점:**
- 100 페이지씩만 처리 (1.6MB)
- Write Lock 시간 단축 (425ms → 5ms)
- 여러 번 실행하여 전체 정리

### 6.2 Background VACUUM Thread

MySQL처럼 백그라운드 스레드 추가:

```kotlin
@Component
class BackgroundVacuumThread(
    private val vacuumService: VacuumService
) {

    @Scheduled(fixedDelay = 60000)  // 60초마다
    fun autoVacuum() {
        val tables = tableService.getAllTables()

        tables.forEach { table ->
            val stats = tableFileManager.getTableStatistics(table.tableName)

            if (stats.deletedRatio >= 0.3) {  // 30% 이상 삭제
                logger.info("Auto VACUUM triggered for ${table.tableName}")
                vacuumService.vacuumTable(table.tableName)
            }
        }
    }
}
```

### 6.3 Online VACUUM (Non-Blocking)

VACUUM 중에도 읽기 허용:

```kotlin
fun onlineVacuum(tableName: String) {
    // 1. Snapshot 생성 (읽기 전용 뷰)
    val snapshot = createSnapshot(tableName)

    // 2. VACUUM 실행 (Write Lock만)
    val activeRows = snapshot.filter { !it.deleted }
    writeTableWithRows(vacuumFile, activeRows)

    // 3. 파일 교체 (짧은 Write Lock)
    acquireWriteLock(tableName, timeout = 1000) {
        file.renameTo(oldFile)
        vacuumFile.renameTo(file)
    }

    // SELECT는 snapshot을 읽으므로 블록되지 않음
}
```

---

## 7. 결론

### MySQL Purge: 고성능 프로덕션용

```
장점: 자동화, 높은 동시성, MVCC 지원
단점: 복잡한 구현, 메모리 오버헤드
적합: 상용 데이터베이스, 높은 TPS 환경
```

### 현재 VACUUM: 교육/프로토타입용

```
장점: 단순한 구현, 명확한 동작
단점: 수동 실행, 낮은 동시성, 느린 속도
적합: 학습 프로젝트, 소규모 데이터베이스
```

### 핵심 차이 요약

| 항목 | MySQL Purge | 현재 VACUUM |
|------|-------------|-------------|
| **실행 방식** | 자동 (백그라운드) | 수동 (명령) |
| **동시성** | 높음 (MVCC) | 낮음 (Write Lock) |
| **속도** | 빠름 (In-Place) | 느림 (Copy-on-Write) |
| **메모리** | 많음 (Undo Log) | 적음 (파일만) |
| **복잡도** | 높음 (수천 줄) | 낮음 (수백 줄) |
| **MVCC** | 지원 | 미지원 |
| **디스크 공간** | 불필요 | 2배 필요 |

### 학습 포인트

1. **Trade-off 이해:** 단순함 vs 성능
2. **Copy-on-Write 패턴:** 동시성 제어 기법
3. **MVCC의 중요성:** 트랜잭션 격리를 위한 핵심 메커니즘
4. **점진적 개선:** Incremental VACUUM, Background Thread 등

이 프로젝트의 VACUUM 구현은 **교육 목적으로 최적화**되어 있으며, MySQL의 복잡성 없이도 **핵심 개념을 이해**할 수 있습니다.
