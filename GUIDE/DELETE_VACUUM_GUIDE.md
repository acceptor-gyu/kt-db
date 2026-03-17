# DELETE와 VACUUM: Tombstone 방식으로 이해하는 데이터베이스 삭제 메커니즘

## 들어가며

데이터베이스에서 `DELETE` 명령을 실행하면 데이터가 즉시 사라질까요? 놀랍게도 대부분의 현대 데이터베이스는 **즉시 삭제하지 않습니다**. 이 글에서는 직접 구현한 파일 기반 데이터베이스의 DELETE와 VACUUM 동작을 통해, MySQL/PostgreSQL과 같은 상용 DB가 삭제를 어떻게 처리하는지 알아보겠습니다.

---

## 1. DELETE의 두 가지 방식

### 1.1 물리적 삭제 (Physical Delete)

가장 직관적인 방법입니다. 파일에서 해당 데이터를 **즉시 제거**하는 방식입니다.

```kotlin
// 메모리 기반: 물리적 삭제 (테스트용)
val remainingRows = it.rows.filter { row ->
    row[columnName] != value  // 조건에 맞지 않는 행만 유지
}
```

**장점:**
- 단순하고 직관적
- 즉시 디스크 공간 회수

**단점:**
- 느림 (파일 전체를 다시 써야 함)
- 트랜잭션 롤백 불가
- MVCC(다중 버전 동시성 제어) 불가능

### 1.2 논리적 삭제 (Logical Delete) - Tombstone 방식

실제로 삭제하지 않고 **"삭제됨" 마킹**만 하는 방식입니다. 이 프로젝트와 MySQL InnoDB가 사용하는 방식입니다.

```kotlin
data class Row(
    val data: Map<String, String>,
    val deleted: Boolean = false,  // 삭제 플래그
    val version: Long = 0          // MVCC용 버전
)
```

**DELETE 실행 과정:**

```kotlin
// 1. 파일에서 모든 Row 읽기 (deleted 포함)
val (schema, allRows) = readTableWithRows(tableName)

// 2. 조건에 맞는 Row의 deleted 플래그를 true로 설정
val updatedRows = allRows.map { row ->
    if (!row.deleted && row.data[columnName] == value) {
        row.markAsDeleted()  // deleted = true
    } else {
        row
    }
}

// 3. 파일에 다시 저장 (deleted=true인 행도 포함)
writeTableWithRows(tableName, schema, updatedRows)
```

**핵심 포인트:**
- `DELETE` 후에도 파일 크기는 그대로
- `SELECT` 시 `deleted=true`인 행은 필터링됨:
  ```kotlin
  // SELECT 실행 시
  if (rowObject.isActive()) {  // deleted == false
      rows.add(rowObject.data)
  }
  ```

---

## 2. 왜 즉시 삭제하지 않을까?

### 2.1 파일 I/O 비용

파일에서 중간 데이터를 삭제하려면:
1. 삭제할 위치 찾기
2. 그 뒤의 모든 데이터를 앞으로 이동 (Shift)
3. 파일 크기 조정

**예시:** 1GB 파일에서 중간 100MB 삭제 시 → 900MB를 디스크에서 다시 쓰기 필요

### 2.2 트랜잭션 & MVCC

```sql
-- Transaction 1
BEGIN;
DELETE FROM users WHERE id = 1;
-- 이 시점에 다른 트랜잭션이 여전히 id=1 읽을 수 있어야 함
COMMIT;
```

Tombstone 방식이면:
- 다른 트랜잭션은 여전히 `deleted=false`인 이전 버전을 읽음
- 커밋 후에야 새로운 스냅샷에서 `deleted=true`로 보임

---

## 3. VACUUM: 공간 회수 메커니즘

Tombstone이 쌓이면 디스크 공간이 낭비됩니다. **VACUUM**은 이를 물리적으로 제거하는 작업입니다.

### 3.1 VACUUM 실행 조건

```kotlin
@ConfigurationProperties(prefix = "vacuum")
data class VacuumConfig(
    var enabled: Boolean = true,
    var thresholdRatio: Double = 0.3,  // 30% 이상 삭제된 행
    var minDeletedRows: Int = 100       // 최소 100개 이상
)

fun shouldVacuum(tableName: String): Boolean {
    val stats = getTableStatistics(tableName)
    return stats.deletedRows >= minDeletedRows &&
           stats.deletedRatio >= thresholdRatio
}
```

**조건:**
- 삭제된 행이 100개 이상
- 전체 행의 30% 이상이 삭제됨

### 3.2 Copy-on-Write 알고리즘

VACUUM의 핵심 도전 과제: **동시 쓰기 감지**

만약 VACUUM 중에 다른 스레드가 `INSERT`나 `DELETE`를 실행하면 데이터 불일치가 발생합니다.

**해결책: 파일 수정 시간 추적**

```kotlin
fun compactTable(tableName: String): VacuumStats {
    // PHASE 1: SNAPSHOT - 파일 수정 시간 기록
    val initialModifiedTime = file.lastModified()  // T1

    // PHASE 2: COPY - 활성 행만 새 파일에 복사
    val activeRows = allRows.filter { !it.deleted }
    writeToVacuumFile(vacuumFile, activeRows)

    // PHASE 3: VERIFICATION - 쓰기 감지 확인
    val finalModifiedTime = file.lastModified()    // T2
    if (initialModifiedTime != finalModifiedTime) {
        // 누군가가 파일을 수정했음 → ABORT
        vacuumFile.delete()
        return VacuumStats.failure("Concurrent write detected")
    }

    // PHASE 4: FILE SWAP - Atomic rename
    file.renameTo(oldFile)
    vacuumFile.renameTo(file)
    oldFile.delete()
}
```

**동작 순서:**

1. **Snapshot (T1):** 파일의 수정 시간을 기록
2. **Copy:** 활성 행만 `.vacuum` 임시 파일에 복사
3. **Verification (T2):** 파일 수정 시간 재확인
   - T1 ≠ T2 → 누군가 파일을 수정 → **ABORT & RETRY**
   - T1 == T2 → 안전 → 파일 교체
4. **Swap:** `users.dat.vacuum` → `users.dat`로 atomic rename

### 3.3 재시도 메커니즘 (Exponential Backoff)

동시 쓰기로 VACUUM이 실패하면 재시도합니다.

```kotlin
fun vacuumTable(tableName: String): VacuumStats {
    var retryCount = 0

    while (retryCount <= maxRetries) {  // 기본 5회
        val result = vacuumTableOnce(tableName)

        if (result.success) {
            return result
        }

        if (!result.abortedDueToWrite) {
            return result  // 쓰기 충돌 외 오류는 재시도 안 함
        }

        retryCount++
        val delay = calculateBackoffDelay(retryCount)  // 1초 → 2초 → 4초 → 8초
        Thread.sleep(delay)
    }

    return VacuumStats.failure("Failed after $maxRetries retries")
}
```

**Exponential Backoff:**
- 1차: 1초 대기
- 2차: 2초 대기
- 3차: 4초 대기
- 4차: 8초 대기
- 5차: 16초 대기

### 3.4 동시성 제어: Read-Write Lock

```kotlin
class VacuumLockManager {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    fun acquireWriteLock(tableName: String): Boolean {
        val lock = getLock(tableName)
        return lock.writeLock().tryLock(5000, TimeUnit.MILLISECONDS)
    }
}
```

**Lock 규칙:**
- `SELECT`, `INSERT`, `DELETE`: Read Lock (여러 스레드 동시 실행 가능)
- `VACUUM`: Write Lock (단독 실행, Read 작업 완료까지 대기)

---

## 4. VACUUM 실행 결과 예시

```kotlin
val stats = tableService.vacuum("users")
println(stats.toSummary())
```

**출력:**
```
VACUUM completed successfully:
- Deleted rows removed: 1500
- Disk space saved: 24MB (35.2%)
- Pages freed: 1536 (4096 → 2560)
- Duration: 234ms
- Retries: 2
```

---

## 5. MySQL InnoDB와 비교

### 5.1 MySQL의 DELETE 동작

MySQL InnoDB도 유사하게 **즉시 삭제하지 않습니다**.

```sql
DELETE FROM users WHERE id = 1;
```

**내부 동작:**
1. 해당 행에 **"delete mark"** 설정 (Tombstone)
2. Undo Log에 이전 버전 기록 (트랜잭션 롤백용)
3. 물리적 삭제는 **Purge Thread**가 나중에 수행

### 5.2 InnoDB의 MVCC

InnoDB는 각 행에 **트랜잭션 ID**를 저장:

```
[row_id | trx_id | roll_ptr | col1 | col2 | ...]
   1       1001      NULL      John   25
```

- `trx_id`: 이 행을 마지막으로 수정한 트랜잭션 ID
- `roll_ptr`: Undo Log 포인터 (이전 버전)

**DELETE 시:**
```
[row_id | trx_id | roll_ptr | deleted | col1 | col2]
   1       1005    0x12AB      1        John   25
```

`deleted=1` 플래그가 설정되지만 행은 남아있음.

### 5.3 Purge Thread (MySQL의 VACUUM)

백그라운드에서 주기적으로 실행:

```c
// InnoDB 내부 (의사 코드)
while (true) {
    for (row in table) {
        if (row.deleted && no_active_transaction_needs_it) {
            physically_remove(row);
        }
    }
    sleep(purge_interval);
}
```

**차이점:**

| 항목 | 이 프로젝트 | MySQL InnoDB |
|------|-------------|--------------|
| 실행 방식 | 수동 `VACUUM` 명령 | 자동 (Purge Thread) |
| 동시 쓰기 감지 | 파일 수정 시간 비교 | MVCC + 트랜잭션 ID |
| 재시도 | Exponential Backoff | 큐 기반 재처리 |
| 트랜잭션 | 미지원 | Undo Log 기반 |

---

## 6. PostgreSQL의 VACUUM

PostgreSQL도 유사하지만 더 명시적입니다:

```sql
-- 수동 VACUUM
VACUUM users;

-- 분석 포함
VACUUM ANALYZE users;

-- 전체 테이블 잠금 후 압축
VACUUM FULL users;
```

**PostgreSQL의 특징:**
- 각 행에 `xmin`(생성 트랜잭션), `xmax`(삭제 트랜잭션) 저장
- `VACUUM`은 온라인 실행 (읽기/쓰기 가능)
- `VACUUM FULL`은 테이블 잠금 후 완전 재작성 (이 프로젝트의 VACUUM과 유사)

---

## 7. 핵심 요약

### DELETE (Tombstone 방식)

```
DELETE users WHERE id=1
     ↓
[Row: {id: 1, deleted: true}]  ← 파일에 여전히 존재
     ↓
SELECT * FROM users  ← deleted=true인 행은 필터링
```

**장점:**
- 빠름 (플래그만 변경)
- MVCC 가능 (다른 트랜잭션이 이전 버전 읽기)
- 트랜잭션 롤백 가능

**단점:**
- 디스크 공간 낭비
- 시간이 지나면 성능 저하 (deleted 행 스캔 필요)

### VACUUM (Copy-on-Write)

```
VACUUM users
     ↓
[활성 행만 복사] → .vacuum 파일 생성
     ↓
[동시 쓰기 감지] → 파일 수정 시간 비교
     ↓
[Atomic Swap] → .vacuum → .dat로 교체
```

**특징:**
- 동시 쓰기 감지 시 자동 재시도 (Exponential Backoff)
- Read-Write Lock으로 동시성 제어
- 디스크 공간 35% 절약 가능

### MySQL/PostgreSQL과의 공통점

1. **즉시 삭제하지 않음** (Tombstone)
2. **백그라운드 정리 작업** (Purge/VACUUM)
3. **MVCC 지원** (트랜잭션 격리)

### 차이점

- MySQL: 자동 Purge Thread
- PostgreSQL: 수동/자동 VACUUM 옵션
- 이 프로젝트: 수동 VACUUM + 파일 수정 시간 기반 충돌 감지

---

## 8. 코드 예시: DELETE → VACUUM 전체 플로우

```kotlin
// 1. DELETE 실행 (논리적 삭제)
tableService.delete("users", "name='Alice'")
// → deleted=true로 마킹

// 2. SELECT로 확인 (deleted 행은 보이지 않음)
val users = tableService.select("users")
// → Alice는 결과에 포함되지 않음

// 3. 파일 크기는 그대로
println(File("users.dat").length())  // 예: 1024KB

// 4. VACUUM 실행 (물리적 삭제)
val stats = tableService.vacuum("users")
println(stats.toSummary())
/*
VACUUM completed successfully:
- Deleted rows removed: 100
- Disk space saved: 256KB (25.0%)
- Duration: 45ms
*/

// 5. 파일 크기 감소
println(File("users.dat").length())  // 예: 768KB
```

---

## 9. 실무 팁

### VACUUM 실행 타이밍

```kotlin
// 안 좋은 예: 매번 DELETE 후 VACUUM
delete("users", "id=1")
vacuum("users")  // 오버헤드 큼

// 좋은 예: 배치로 일괄 삭제 후 VACUUM
repeat(1000) {
    delete("users", "id=$it")
}
vacuum("users")  // 1회만 실행
```

### Auto VACUUM 설정

```properties
vacuum.enabled=true
vacuum.threshold-ratio=0.3        # 30% 이상 삭제 시
vacuum.min-deleted-rows=100       # 최소 100개 이상
vacuum.scan-interval-seconds=60   # 60초마다 체크
```

### 디스크 공간 체크

VACUUM은 임시로 2배의 공간이 필요합니다.

```kotlin
// VACUUM 전 디스크 공간 확인
val tableSize = getTableStatistics("users").fileSizeBytes
val freeSpace = dataDirectory.usableSpace
if (freeSpace < tableSize * 2) {
    throw InsufficientSpaceException()
}
```

---

## 10. 결론

**DELETE는 즉시 삭제하지 않습니다.** 이는 성능과 트랜잭션 안정성을 위한 설계입니다.

- **Tombstone 방식:** 삭제 플래그만 설정 (빠름, MVCC 가능)
- **VACUUM:** 주기적으로 물리적 삭제 (디스크 공간 회수)
- **Copy-on-Write:** 동시 쓰기 감지로 데이터 일관성 보장

MySQL InnoDB, PostgreSQL도 동일한 원리로 동작하며, 차이는 자동화 수준과 MVCC 구현 방식뿐입니다.

---

## 참고 자료

- [MySQL InnoDB Architecture](https://dev.mysql.com/doc/refman/8.0/en/innodb-architecture.html)
- [PostgreSQL VACUUM](https://www.postgresql.org/docs/current/sql-vacuum.html)
- [프로젝트 소스 코드](https://github.com/yourusername/kt-db)
  - `TableService.kt:139` - DELETE 구현
  - `TableFileManager.kt:585` - Tombstone 마킹
  - `TableFileManager.kt:671` - VACUUM (Copy-on-Write)
  - `VacuumService.kt:93` - 재시도 로직
