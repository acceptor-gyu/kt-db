# ACID 트랜잭션 구현 계획서

## 관련 TIL 학습 노트

| 주제 | TIL 문서 | 관련 섹션 |
|------|----------|-----------|
| ACID 전체 개요 | [트랜잭션 ACID](https://github.com/acceptor-gyu/TIL/blob/main/Database/260314_01_트랜잭션_ACID.md) | 2~5장 전체 |
| Undo/Redo Log | [MySQL 핵심 로그와 활용도](https://github.com/acceptor-gyu/TIL/blob/main/Database/260314_02_MySQL_핵심_로그와_활용도.md) | 2장 Atomicity, 5장 Durability |
| MVCC | [MySQL MVCC](https://github.com/acceptor-gyu/TIL/blob/main/Database/260220_03_RDBMS_MySQL_MVCC.md) | 4장 Isolation |
| 트랜잭션 격리 수준 | [MySQL 트랜잭션 격리 수준](https://github.com/acceptor-gyu/TIL/blob/main/Database/260220_02_RDBMS_MySQL_트랜잭션_격리_수준.md) | 4장 Isolation |
| Lock 종류 | [MySQL Lock 종류](https://github.com/acceptor-gyu/TIL/blob/main/Database/260220_01_RDBMS_MySQL_Lock_종류.md) | 4장 Isolation (LockMgr) |
| 2PL 프로토콜 | [2 Phase Locking](https://github.com/acceptor-gyu/TIL/blob/main/Database/260223_01_2_Phase_Locking.md) | 4장 Isolation (LockMgr) |
| 비관적/낙관적 락 | [비관적 락과 낙관적 락](https://github.com/acceptor-gyu/TIL/blob/main/Database/260216_01_비관적_락과_낙관적_락.md) | 4장 Isolation |
| DELETE & Tombstone | [RDBMS DELETE](https://github.com/acceptor-gyu/TIL/blob/main/Database/260127_01_RDBMS_DELETE.md) | 2장 Atomicity, 7장 Step 7 Purge |
| Purge & VACUUM | [MySQL PURGE](https://github.com/acceptor-gyu/TIL/blob/main/Database/260127_02_MYSQL_PURGE.md) | 2.6 Undo Log Purge, 7장 Step 7 |
| B+Tree 인덱스 | [MySQL index와 B+Tree](https://github.com/acceptor-gyu/TIL/blob/main/Database/260220_04_RDBMS_MySQL_index와_B+Tree.md) | (향후 인덱스 구현 시 참고) |
| Random vs Sequential I/O | [MySQL IO 종류](https://github.com/acceptor-gyu/TIL/blob/main/Database/260312_01_MySQL_IO_종류_Random_Access_vs_Sequential_Access.md) | 5.7 Doublewrite Buffer (Sequential Write) |

---

## 1. 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Client (api-server / TCP)                        │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │ SQL (BEGIN, INSERT, COMMIT, ...)
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     ConnectionHandler (per-connection)                  │
│  ┌──────────────┐                                                       │
│  │ TransactionCtx│ ← txId, isolationLevel, readView, undoLogChain       │
│  └──────────────┘                                                       │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                        TransactionManager                                │
│  ┌────────────┐  ┌───────────────┐  ┌───────────┐  ┌──────────────────┐  │
│  │ TxIdGen    │  │  ActiveTxMap  │  │ LockMgr   │  │ IsolationPolicy  │  │
│  │(AtomicLong)│  │(ConcurrentMap)│  │(Row-level)│  │ (RC / RR)        │  │
│  └────────────┘  └───────────────┘  └───────────┘  └──────────────────┘  │
└──────────┬──────────────┬───────────────┬────────────────┬───────────────┘
           │              │               │                │
           ▼              ▼               ▼                ▼
┌──────────────┐ ┌──────────────┐ ┌─────────────┐ ┌──────────────────┐
│  Undo Log    │ │  WAL (Redo)  │ │ MVCC Engine │ │ Doublewrite Buf  │
│  Manager     │ │  Manager     │ │             │ │                  │
│              │ │              │ │ - ReadView  │ │ - 2x Page Copy   │
│ - 변경 전 값   │ │ - 변경 후 값   │ │ - Snapshot  │ │ - Torn Page 방지   │
│ - Rollback용 │ │ - Recovery용  │ │ - Visibility│ │                  │
└──────┬───────┘ └──────┬───────┘ └──────┬──────┘ └─────────┬────────┘
       │                │                │                  │
       ▼                ▼                ▼                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          BufferPool (기존)                               │
│  ┌──────────┐  ┌───────────┐  ┌──────────────┐  ┌──────────────────┐    │
│  │ Page[]   │  │ DirtyPages│  │ LRU Eviction │  │ FlushPolicy      │    │
│  │ (16KB)   │  │ (LSN기반)  │  │              │  │ (Checkpoint연동)  │    │
│  └──────────┘  └───────────┘  └──────────────┘  └──────────────────┘    │
└──────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Disk (TableFileManager 기존)                      │
│  ┌────────────┐  ┌────────────┐  ┌─────────────┐  ┌────────────────┐    │
│  │ *.dat      │  │ *.undo     │  │ wal.log     │  │ doublewrite.buf│    │
│  │ (data file)│  │ (undo log) │  │ (redo log)  │  │ (DWB file)     │    │
│  └────────────┘  └────────────┘  └─────────────┘  └────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Atomicity (원자성) — Undo Log 기반 Rollback

### 2.1 개념

Atomicity는 트랜잭션 내 모든 연산이 **전부 반영되거나 전부 취소**되어야 한다는 성질이다. 각 DML(INSERT/UPDATE/DELETE) 수행 전에 "되돌리기 위한 정보"를 **Undo Log**에 먼저 기록하고, ROLLBACK 시 이 로그를 역순으로 적용하여 원래 상태를 복원한다.

```
핵심 원칙: "변경하기 전에, 되돌리는 방법을 먼저 기록한다"

INSERT → Undo: "이 행을 삭제하라"
UPDATE → Undo: "이 행을 이전 값으로 복원하라"
DELETE → Undo: "이 행을 다시 삽입하라"
```

### 2.2 Undo Log 레코드 구조

```kotlin
data class UndoLogRecord(
    val lsn: Long,                    // Undo 레코드 고유 시퀀스 번호
    val txId: Long,                   // 트랜잭션 ID
    val tableName: String,            // 대상 테이블
    val operationType: UndoOpType,    // INSERT_UNDO, UPDATE_UNDO, DELETE_UNDO
    val rowId: Long,                  // 대상 행의 물리적 식별자 (pageNo + slotNo)
    val beforeImage: ByteArray?,      // 변경 전 행 데이터 (UPDATE/DELETE용)
    val afterImage: ByteArray?,       // 변경 후 행 데이터 (INSERT용 - rollback시 삭제 대상)
    val prevUndoLsn: Long,            // 같은 트랜잭션의 이전 Undo 레코드 (체인)
    val timestamp: Long               // 기록 시각
)

enum class UndoOpType {
    INSERT_UNDO,   // Rollback시: 삽입된 행을 삭제
    UPDATE_UNDO,   // Rollback시: beforeImage로 복원
    DELETE_UNDO    // Rollback시: beforeImage를 다시 삽입
}
```

### 2.3 Undo Log 체인 관리

각 트랜잭션은 자신의 Undo Log 레코드들을 **역방향 연결 리스트(체인)**로 관리한다.

```
Transaction TX-42:
  INSERT row A → UndoLog#100 (prevUndoLsn = 0)       ← 체인 시작
  UPDATE row B → UndoLog#105 (prevUndoLsn = 100)
  DELETE row C → UndoLog#110 (prevUndoLsn = 105)     ← lastUndoLsn

ROLLBACK TX-42 (역순 적용):
  1. UndoLog#110 (DELETE_UNDO) → row C의 beforeImage 복원 (re-insert)
  2. UndoLog#105 (UPDATE_UNDO) → row B의 beforeImage 복원
  3. UndoLog#100 (INSERT_UNDO) → row A 삭제
  4. prevUndoLsn == 0 → 체인 종료
```

### 2.4 Undo Log 파일 형식

```
[Undo Log File: ./data/undo.log]
┌────────────────────────────────────┐
│ File Header (16 bytes)             │
│  - Magic: 0xUDLG (4B)              │
│  - Version: 1 (2B)                 │
│  - NextLSN: AtomicLong (8B)        │
│  - Reserved (2B)                   │
├────────────────────────────────────┤
│ UndoLogRecord #1                   │
│  - RecordLength (4B)               │
│  - LSN (8B)                        │
│  - TxId (8B)                       │
│  - OpType (1B)                     │
│  - TableNameLen (2B) + TableName   │
│  - RowId (8B)                      │
│  - BeforeImageLen (4B) + Data      │
│  - AfterImageLen (4B) + Data       │
│  - PrevUndoLsn (8B)                │
│  - Timestamp (8B)                  │
├────────────────────────────────────┤
│ UndoLogRecord #2 ...               │
└────────────────────────────────────┘
```

### 2.5 Rollback 메커니즘

```kotlin
class UndoLogManager(
    private val undoLogFile: File,
    private val bufferPool: BufferPool
) {
    private val nextLsn = AtomicLong(1)

    /** Undo Log 레코드 추가 */
    fun appendUndoLog(record: UndoLogRecord): Long { ... }

    /** 특정 트랜잭션의 Undo Log를 역순으로 적용 (Rollback) */
    fun rollback(txId: Long, lastUndoLsn: Long) {
        var currentLsn = lastUndoLsn
        while (currentLsn != 0L) {
            val record = readUndoRecord(currentLsn)
            applyUndo(record)
            currentLsn = record.prevUndoLsn
        }
    }

    /** 단일 Undo 적용 */
    private fun applyUndo(record: UndoLogRecord) {
        when (record.operationType) {
            INSERT_UNDO -> {
                // 삽입된 행을 삭제 (delete mark)
                deleteRowFromPage(record.tableName, record.rowId)
            }
            UPDATE_UNDO -> {
                // beforeImage로 행 데이터 복원
                restoreRowInPage(record.tableName, record.rowId, record.beforeImage!!)
            }
            DELETE_UNDO -> {
                // 삭제된 행을 다시 활성화 (undelete)
                undeleteRowInPage(record.tableName, record.rowId, record.beforeImage!!)
            }
        }
    }
}
```

### 2.6 Undo Log Purge (생명주기)

Undo Log는 무한히 쌓이면 안 되므로, 참조하는 활성 트랜잭션이 없을 때 정리(Purge)한다.

```
Purge 조건:
1. 해당 Undo Log를 생성한 트랜잭션이 COMMITTED 상태
2. 해당 beforeImage를 읽어야 하는 MVCC ReadView가 없음
   → 가장 오래된 활성 트랜잭션의 txId보다 작은 txId의 Undo Log는 안전하게 삭제

구현:
- 기존 VacuumScheduler 패턴 활용
- UndoPurgeService가 TransactionManager에서 min(activeTxId)를 조회
- min(activeTxId)보다 작은 txId의 committed Undo Log를 파일에서 제거
```

### 2.7 현재 코드와의 연결점

| 기존 코드 | 변경 내용 |
|-----------|----------|
| `Row.kt` (version 필드) | Undo Log의 version tracking에 활용 |
| `RowEncoder.encodeRow()` | Undo Log의 beforeImage/afterImage 직렬화에 재사용 |
| `TableService.insert()` — `compute()` 내부 | Undo Log append 호출 추가 |
| `TableService.delete()` | tombstone 마킹 전에 beforeImage 기록 |

---

## 3. Consistency (일관성)

### 3.1 개념

트랜잭션 완료 후에도 데이터베이스가 정의된 규칙(제약 조건)을 만족해야 한다. **Atomicity가 Consistency의 기반**이 된다 — 제약 조건 위반 시 Undo Log를 통해 원래 상태로 복원.

### 3.2 제약 조건 검증 시점

```
IMMEDIATE (각 DML 실행 시 즉시 검증):
- INSERT 시: NOT NULL, 타입 검증, UNIQUE 중복 검사, FK 참조 확인
- UPDATE 시: 변경 후 값에 대해 동일 검증
- DELETE 시: FK 역참조 확인 (자식 테이블)
- 위반 시 해당 문장만 실패하거나 트랜잭션 전체 ROLLBACK

DEFERRED (COMMIT 시 일괄 검증):
- 순환 FK 같은 복잡한 상황에서 필요 (향후 확장)
```

### 3.3 일관성 보장 전략

```kotlin
class ConstraintValidator(private val tableService: TableService) {
    fun validateInsert(tableName: String, values: Map<String, String>, txCtx: TransactionContext) {
        // 1. 기존 타입 검증 (현재 Resolver.validateInsertData 로직)
        // 2. NOT NULL 검증 (Phase 4 이후)
        // 3. UNIQUE/PK 중복 검사 - 같은 트랜잭션 내 미커밋 데이터도 고려
        // 4. FK 참조 무결성 (Phase 9 이후)
    }
}
```

---

## 4. Isolation (격리성) — MVCC 기반

### 4.1 개념

**MVCC(Multi-Version Concurrency Control)**는 행 데이터의 여러 버전을 유지하여, **읽기가 쓰기를 블로킹하지 않도록** 하는 기법이다. InnoDB에서는 Undo Log에 저장된 이전 버전을 통해 구현한다.

```
쓰기 트랜잭션이 행을 수정해도,
읽기 트랜잭션은 Undo Log에서 자신에게 "보이는" 이전 버전을 읽는다.
→ Lock 없이 읽기 가능 (Non-blocking Read)
```

### 4.2 행 레벨 MVCC 필드

현재 `Row`에 `version` 필드가 존재하지만, MVCC를 위해 추가 필드가 필요하다.

```kotlin
data class RowMvccHeader(
    val createdTxId: Long,      // 이 버전을 생성한 트랜잭션 ID (= InnoDB DB_TRX_ID)
    val deletedTxId: Long?,     // 이 행을 삭제한 트랜잭션 ID (null이면 활성)
    val undoLogPointer: Long    // 이전 버전을 가리키는 Undo Log LSN (= InnoDB DB_ROLL_PTR)
)
```

바이너리 인코딩 확장:

```
현재 Row 바이너리:
[RowLength: 4B][Deleted: 1B][Version: 8B][Field1][Field2]...

MVCC 확장:
[RowLength: 4B][Deleted: 1B][Version: 8B][CreatedTxId: 8B][DeletedTxId: 8B][UndoPtr: 8B][Field1][Field2]...
                                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                          +24 bytes 추가
```

### 4.3 ReadView (스냅샷)

ReadView는 "어떤 트랜잭션의 결과가 보이는가"를 결정하는 핵심 구조이다.

```kotlin
data class ReadView(
    val creatorTxId: Long,
    val minTxId: Long,          // 이보다 작은 txId → 무조건 보임 (이미 커밋됨)
    val maxTxId: Long,          // 이 이상의 txId → 무조건 안 보임 (미래 트랜잭션)
    val activeTxIds: Set<Long>  // minTxId~maxTxId 사이에서 아직 커밋 안 된 것들
)
```

### 4.4 가시성 판단 알고리즘

```
행의 createdTxId가 row_txid일 때:

1. row_txid == 내 txId       → 보임 (내가 만든 것)
2. row_txid < minTxId        → 보임 (ReadView 이전에 커밋 완료)
3. row_txid >= maxTxId       → 안 보임 (ReadView 이후 시작된 트랜잭션)
4. minTxId <= row_txid < maxTxId:
   - activeTxIds에 있으면    → 안 보임 (아직 커밋 안 됨)
   - activeTxIds에 없으면    → 보임 (이미 커밋됨)

안 보이는 경우:
  → undoLogPointer를 따라가서 이전 버전을 확인 (재귀적으로 반복)
  → 보이는 버전을 찾을 때까지 Undo 체인을 역추적
  → 모든 버전이 안 보이면 → 결과에서 제외
```

### 4.5 격리 수준별 ReadView 생성 전략

```
┌───────────────────────────────────────────────────────────────────┐
│ READ COMMITTED (RC)                                               │
│                                                                   │
│ TX-1: BEGIN                                                       │
│ TX-1: SELECT * FROM users     ← ReadView A 생성                    │
│                                                                   │
│       TX-2: INSERT ... COMMIT                                     │
│                                                                   │
│ TX-1: SELECT * FROM users     ← ReadView B 생성 (TX-2 결과 보임)     │
│                                                                   │
│ → 매 SELECT마다 새 ReadView → Non-repeatable Read 발생 가능           │
├───────────────────────────────────────────────────────────────────┤
│ REPEATABLE READ (RR, 기본값)                                        │
│                                                                   │
│ TX-1: BEGIN                                                       │
│ TX-1: SELECT * FROM users     ← ReadView A 생성                    │
│                                                                   │
│       TX-2: INSERT ... COMMIT                                     │
│                                                                   │
│ TX-1: SELECT * FROM users     ← ReadView A 재사용 (TX-2 안 보임)     │
│                                                                   │
│ → 트랜잭션당 하나의 ReadView → 항상 같은 스냅샷                           │
│ → InnoDB에서는 Gap Lock으로 Phantom Read도 방지                       │
└───────────────────────────────────────────────────────────────────┘
```

```kotlin
enum class IsolationLevel {
    READ_COMMITTED,    // 매 SELECT마다 새 ReadView
    REPEATABLE_READ    // 트랜잭션 첫 SELECT에서 ReadView 고정
}

class TransactionContext(
    val txId: Long,
    val isolationLevel: IsolationLevel = IsolationLevel.REPEATABLE_READ,
    var readView: ReadView? = null,
    var lastUndoLsn: Long = 0,
    var state: TxState = TxState.ACTIVE
)

enum class TxState { ACTIVE, COMMITTED, ABORTED }
```

### 4.6 MVCC SELECT 흐름

```
현재: TableFileManager → 전체 행 반환
MVCC:
  1. TableFileManager/BufferPool에서 페이지를 읽음 (기존과 동일)
  2. 각 행에 대해 ReadView 기반 가시성 판단
  3. 안 보이는 행 → undoLogPointer를 따라 이전 버전 확인
  4. 보이는 버전을 찾을 때까지 Undo 체인 역추적
  5. 모든 버전이 안 보이면 → 결과에서 제외
  6. 가시적인 행만 모아서 반환
```

---

## 5. Durability (영속성) — WAL + Doublewrite Buffer

### 5.1 WAL (Write-Ahead Log / Redo Log) 개념

```
핵심 원칙 (Write-Ahead Rule):
"데이터 페이지를 디스크에 쓰기 전에,
 반드시 해당 변경에 대한 로그를 먼저 디스크에 기록한다"

왜?
- 데이터 페이지 flush는 Random I/O (느림)
- WAL append는 Sequential I/O (빠름)
- COMMIT 시 WAL만 fsync하면 영속성 보장
- 실제 데이터 페이지는 나중에 Checkpoint에서 flush (지연 쓰기)

크래시 발생 시:
- WAL에 기록된 COMMIT은 반영 보장
- WAL을 Redo(재실행)하여 데이터 페이지를 복구
```

### 5.2 WAL 레코드 구조

```kotlin
data class WalRecord(
    val lsn: Long,                // Log Sequence Number (단조 증가)
    val txId: Long,               // 트랜잭션 ID
    val type: WalRecordType,      // 레코드 유형
    val tableName: String,        // 대상 테이블
    val pageNumber: Int,          // 대상 페이지 번호
    val offset: Int,              // 페이지 내 오프셋
    val beforeImage: ByteArray?,  // 변경 전 (physiological logging)
    val afterImage: ByteArray,    // 변경 후 데이터 (Redo 시 이것을 적용)
    val prevLsn: Long,            // 같은 트랜잭션의 이전 WAL LSN
    val timestamp: Long
)

enum class WalRecordType {
    INSERT,           // 행 삽입
    UPDATE,           // 행 수정
    DELETE,           // 행 삭제 (tombstone)
    PAGE_SPLIT,       // 페이지 분할
    COMMIT,           // 트랜잭션 커밋
    ABORT,            // 트랜잭션 중단
    CHECKPOINT_BEGIN, // 체크포인트 시작
    CHECKPOINT_END    // 체크포인트 완료
}
```

### 5.3 LSN (Log Sequence Number) 관리

```
LSN = WAL 파일 내에서의 바이트 오프셋 (단조 증가)

┌───────────────────────────────────────────────────────────────┐
│ WAL File                                                      │
│ ┌─────────┬─────────┬─────────┬─────────┬─────────┬────────┐  │
│ │ Record  │ Record  │ Record  │ Record  │ Record  │ ...    │  │
│ │ LSN=0   │ LSN=128 │ LSN=300 │ LSN=450 │ LSN=600 │        │  │
│ └─────────┴─────────┴─────────┴─────────┴─────────┴────────┘  │
│                                            ▲          ▲       │
│                                       flushedLSN   nextLSN    │
└───────────────────────────────────────────────────────────────┘

BufferPool Page:
- 각 Page에 pageLSN 필드 기록
- pageLSN = "이 페이지에 마지막으로 적용된 WAL 레코드의 LSN"

COMMIT 시:
- COMMIT WAL 레코드의 LSN = commitLSN
- flushedLSN >= commitLSN 이어야 COMMIT 완료 (Write-Ahead 보장)

Checkpoint 시:
- checkpointLSN 기록 → 크래시 복구 시 이 이후만 Redo
```

### 5.4 WAL 파일 형식

```
[WAL File: ./data/wal.log]
┌─────────────────────────────────────┐
│ WAL File Header (32 bytes)          │
│  - Magic: 0xWALG (4B)               │
│  - Version: 1 (2B)                  │
│  - NextLSN (8B)                     │
│  - FlushedLSN (8B)                  │
│  - CheckpointLSN (8B)               │
│  - Reserved (2B)                    │
├─────────────────────────────────────┤
│ WAL Record #1                       │
│  - RecordLength (4B)                │
│  - LSN (8B)                         │
│  - TxId (8B)                        │
│  - Type (1B)                        │
│  - TableNameLen (2B) + TableName    │
│  - PageNumber (4B)                  │
│  - Offset (4B)                      │
│  - AfterImageLen (4B) + Data        │
│  - PrevLSN (8B)                     │
│  - Checksum (4B)                    │
├─────────────────────────────────────┤
│ WAL Record #2 ...                   │
└─────────────────────────────────────┘
```

### 5.5 WAL Manager

```kotlin
class WalManager(
    private val walFile: File,
    private val walBufferSize: Int = 64 * 1024  // 64KB 메모리 버퍼
) {
    private val nextLsn = AtomicLong(1)
    private val flushedLsn = AtomicLong(0)
    private val walBuffer = ByteArrayOutputStream(walBufferSize)
    private val bufferLock = ReentrantLock()

    /** WAL 레코드 append (메모리 버퍼에 추가) */
    fun append(record: WalRecord): Long {
        bufferLock.withLock {
            val bytes = serialize(record)
            walBuffer.write(bytes)
            return record.lsn
        }
    }

    /** WAL 버퍼를 디스크에 flush (fsync) */
    fun flush(upToLsn: Long) {
        bufferLock.withLock {
            walFile.appendBytes(walBuffer.toByteArray())
            walFile.sync()  // fsync 보장
            flushedLsn.set(upToLsn)
            walBuffer.reset()
        }
    }

    /** COMMIT 시: commitLSN까지 WAL이 flush되었는지 보장 */
    fun ensureFlushed(commitLsn: Long) {
        if (flushedLsn.get() < commitLsn) {
            flush(commitLsn)
        }
    }
}
```

### 5.6 Checkpoint 메커니즘

Checkpoint = "이 시점까지의 변경이 데이터 파일에 반영됨"을 표시하여, 크래시 복구 시 Redo 범위를 줄인다.

```
Checkpoint 없이 크래시 복구:
  WAL 시작부터 끝까지 전부 Redo → 느림

Checkpoint 있으면:
  CheckpointLSN 이후만 Redo → 빠름

Fuzzy Checkpoint 과정:
  1. CHECKPOINT_BEGIN WAL 레코드 기록 (checkpointLsn)
  2. 활성 트랜잭션 목록 + dirty page 목록을 WAL에 기록
  3. BufferPool dirty pages를 Doublewrite Buffer를 통해 디스크에 flush
  4. CHECKPOINT_END WAL 레코드 기록
  5. WAL 헤더의 CheckpointLSN 갱신
  6. CheckpointLSN 이전의 WAL 레코드 삭제 가능 (로그 순환)
```

```kotlin
class CheckpointManager(
    private val walManager: WalManager,
    private val bufferPool: BufferPool,
    private val intervalMs: Long = 60_000  // 1분마다
) {
    fun performCheckpoint() {
        val beginLsn = walManager.append(WalRecord(type = CHECKPOINT_BEGIN, ...))
        bufferPool.flushDirtyPages(throughDoublewriteBuffer = true)
        walManager.append(WalRecord(type = CHECKPOINT_END, ...))
        walManager.updateCheckpointLsn(beginLsn)
    }
}
```

### 5.7 Doublewrite Buffer

#### 왜 필요한가: Partial Write (Torn Page) 문제

```
문제 상황:
  1. BufferPool에서 16KB dirty page를 디스크에 flush
  2. OS가 4KB 블록 단위로 쓰기 수행 (16KB = 4블록)
  3. 2번째 블록 쓰기 중 크래시 발생!

  결과:
  ┌─────────────────────────────────────────────┐
  │ Page on Disk (16KB)                         │
  ├───────────┬───────────┬──────────┬──────────┤
  │ Block 1   │ Block 2   │ Block 3  │ Block 4  │
  │ 새 데이터   │ 절반 새     │ 옛 데이터  │ 옛 데이터  │
  │   (4KB)   │  (4KB)    │  (4KB)   │  (4KB)   │
  └───────────┴───────────┴──────────┴──────────┘
  → 페이지가 깨짐 (Torn Page)

WAL만으로 부족한 이유:
  - WAL은 "변경분(delta)"만 기록 (physiological logging)
  - Redo 적용 = "정상 기존 페이지" + "변경분" = "새 페이지"
  - 기존 페이지가 깨져 있으면 변경분을 적용할 수 없음!
```

#### Doublewrite Buffer 동작 원리

```
┌─────────────────────────────────────────────────────────────────────┐
│                        BufferPool                                   │
│                    (dirty pages: P1, P2, P3)                        │
└────────────────────────────┬────────────────────────────────────────┘
                             │
               ┌─────────────▼──────────────────┐
    STEP 1:    │   Doublewrite Buffer File      │
    Sequential │   (연속 영역에 순차 쓰기)           │
    Write      │                                │
               │   [Page1][Page2][Page3]        │
               │                                │
               │   + fsync()  ← 안전하게 기록      │
               └─────────────┬──────────────────┘
                             │
               ┌─────────────▼──────────────────┐
    STEP 2:    │   Actual Data Files            │
    Random     │   (각 페이지의 원래 위치)           │
    Write      │                                │
               │   P1 → users.dat:offset_0      │
               │   P2 → users.dat:offset_16384  │
               │   P3 → orders.dat:offset_0     │
               └────────────────────────────────┘

복구 시나리오:

  A) STEP 1에서 크래시:
     - Doublewrite Buffer 불완전 → 무시
     - 원본 데이터 파일은 옛 버전이므로 정상
     - WAL Redo로 복구 가능 ✓

  B) STEP 2에서 크래시 (Torn Page 발생):
     - 데이터 파일의 일부 페이지가 깨짐
     - Doublewrite Buffer에 완전한 복사본 있음!
     - DWB에서 깨진 페이지 복원 → WAL Redo 적용 ✓

  → 어느 시점에서 크래시해도 복구 가능
```

#### Doublewrite Buffer 파일 구조

```
[Doublewrite Buffer File: ./data/doublewrite.buf]
┌─────────────────────────────────────┐
│ Header (16 bytes)                   │
│  - Magic: 0xDWBF (4B)               │
│  - PageCount (4B)                   │
│  - LSN (8B)                         │
├─────────────────────────────────────┤
│ Page Metadata Array                 │
│  [0]: tableName(32B), pageNo(4B)    │
│  [1]: tableName(32B), pageNo(4B)    │
├─────────────────────────────────────┤
│ Page Data Block 1 (16KB)            │
│ Page Data Block 2 (16KB)            │
│ ...                                 │
└─────────────────────────────────────┘
```

```kotlin
class DoublewriteBuffer(
    private val dwbFile: File,
    private val batchSize: Int = 64  // 한 번에 64페이지 (1MB)
) {
    fun writePages(pages: List<Page>, pageWriter: (Page) -> Unit) {
        // STEP 1: DWB에 순차 쓰기 + fsync
        RandomAccessFile(dwbFile, "rw").use { raf ->
            writeHeader(raf, pages.size)
            writeMetadata(raf, pages)
            pages.forEach { page -> raf.write(page.data) }
            raf.fd.sync()  // fsync
        }

        // STEP 2: 각 페이지를 원래 데이터 파일 위치에 쓰기
        pages.forEach { page -> pageWriter(page) }

        // STEP 3: DWB 클리어
        clearBuffer()
    }

    /** 크래시 복구: torn page 감지 및 DWB에서 복원 */
    fun recoverTornPages(dataFileReader: (PageId) -> Page?): List<PageId> {
        val recovered = mutableListOf<PageId>()
        readDwbPages().forEach { (pageId, dwbData) ->
            val current = dataFileReader(pageId)
            if (current != null && isPageCorrupted(current)) {
                restorePageFromDwb(pageId, dwbData)
                recovered.add(pageId)
            }
        }
        return recovered
    }

    /** 페이지 손상 여부 (체크섬 비교) */
    private fun isPageCorrupted(page: Page): Boolean {
        val stored = extractChecksum(page.data)
        val calculated = calculateChecksum(page.data)
        return stored != calculated
    }
}
```

### 5.8 크래시 복구 (Crash Recovery) 플로우

```
서버 시작 시 자동 실행: CrashRecoveryManager.recover()

┌─────────────────────────────────────────────────────────────────┐
│ Phase 0: Doublewrite Buffer Recovery                            │
│                                                                 │
│  1. DWB 파일 읽기                                                 │
│  2. 각 데이터 파일의 페이지 checksum 검증                             │
│  3. Torn page 발견 시 DWB에서 완전한 페이지로 복원                     │
│  → 모든 데이터 페이지가 "일관된 상태"임을 보장                            │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 1: Analysis (분석)                                         │
│                                                                 │
│  1. WAL의 마지막 CheckpointLSN 위치 확인                            │
│  2. CheckpointLSN 이후의 WAL 레코드를 순방향 스캔                     │
│  3. Redo 필요한 페이지 목록 수집 (dirty page table)                  │
│  4. 미완료 트랜잭션 목록 수집 (active tx set)                         │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 2: Redo (재실행)                                            │
│                                                                 │
│  1. CheckpointLSN 이후의 WAL 레코드를 순방향으로 재실행                 │
│  2. 각 레코드의 LSN > 해당 페이지의 pageLSN일 때만 적용                  │
│     (이미 반영된 변경은 skip — 멱등성 보장)                            │
│  3. COMMITTED든 ACTIVE든 관계없이 모든 변경을 Redo                    │
│  → 크래시 직전의 메모리 상태를 디스크에 복원                              │
└────────────────────────┬────────────────────────────────────────┘
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│ Phase 3: Undo (취소)                                             │
│                                                                 │
│  1. Phase 1에서 수집한 미완료(ACTIVE) 트랜잭션들                       │
│  2. 각 트랜잭션의 Undo Log를 역순으로 적용                             │
│  3. UndoLogManager.rollback(txId, lastUndoLsn) 호출              │
│  → 커밋되지 않은 트랜잭션의 효과를 제거                                  │
└─────────────────────────────────────────────────────────────────┘
```

```kotlin
class CrashRecoveryManager(
    private val walManager: WalManager,
    private val undoLogManager: UndoLogManager,
    private val doublewriteBuffer: DoublewriteBuffer,
    private val bufferPool: BufferPool,
    private val tableFileManager: TableFileManager
) {
    fun recover() {
        logger.info("=== Crash Recovery Started ===")

        // Phase 0: DWB Recovery
        val tornPages = doublewriteBuffer.recoverTornPages { pageId ->
            tableFileManager.readPageFromDisk(pageId.tableName, pageId.pageNumber)
        }
        logger.info("Phase 0: Recovered ${tornPages.size} torn pages")

        // Phase 1: Analysis
        val checkpointLsn = walManager.getCheckpointLsn()
        val (dirtyPageTable, activeTxSet) = analyzeWal(checkpointLsn)

        // Phase 2: Redo
        val redoCount = redoPass(checkpointLsn, dirtyPageTable)
        logger.info("Phase 2: $redoCount records replayed")

        // Phase 3: Undo
        val undoCount = undoPass(activeTxSet)
        logger.info("Phase 3: $undoCount transactions rolled back")

        logger.info("=== Crash Recovery Completed ===")
    }
}
```

---

## 6. DML별 ACID 보장 전체 흐름

### 6.1 INSERT 흐름

```
Client: INSERT INTO users VALUES (id="1", name="Alice")

  1. [ConnectionHandler] SQL 파싱
  2. [TransactionManager] txId 할당 (autocommit이면 암묵적 트랜잭션)
  3. [UndoLogManager] INSERT_UNDO 기록
     - beforeImage: null
     - afterImage: 새 행의 rowId (rollback 시 삭제 대상)
  4. [WalManager] INSERT WAL 레코드 기록 (afterImage = 새 행 데이터)
  5. [BufferPool] 대상 페이지를 메모리에서 수정
     - 행 헤더: createdTxId = 현재 txId
     - pageLSN = WAL 레코드의 LSN
     - dirty page 마킹
  6. [COMMIT 시] WalManager.flush(commitLsn) → fsync
  7. [Checkpoint 시] dirty page → DWB → 데이터 파일
```

### 6.2 UPDATE 흐름

```
Client: UPDATE users SET name="Bob" WHERE id=1

  1. [ConnectionHandler] SQL 파싱
  2. [BufferPool] 대상 행 페이지 로드
  3. [UndoLogManager] UPDATE_UNDO 기록
     - beforeImage: 변경 전 행 전체 데이터
     - undoPointer: 이전 Undo LSN
  4. [WalManager] UPDATE WAL 레코드 기록 (afterImage = 변경 후 데이터)
  5. [BufferPool] 페이지 내 행을 직접 수정 (in-place update)
     - createdTxId = 현재 txId
     - undoLogPointer = 방금 기록한 Undo LSN
     - version++, pageLSN 갱신, dirty 마킹
  6. [COMMIT 시] WAL flush
```

### 6.3 DELETE 흐름

```
Client: DELETE FROM users WHERE id=1

  1. [ConnectionHandler] SQL 파싱 + WHERE 평가
  2. [BufferPool] 대상 행 페이지 로드
  3. [UndoLogManager] DELETE_UNDO 기록
     - beforeImage: 삭제될 행의 전체 데이터
  4. [WalManager] DELETE WAL 레코드 기록
  5. [BufferPool] 행의 deletedTxId = 현재 txId (tombstone)
     - 물리적 삭제는 VACUUM/Purge에서 수행
     - pageLSN 갱신, dirty 마킹
  6. [COMMIT 시] WAL flush
```

### 6.4 SELECT 흐름 (MVCC)

```
Client: SELECT * FROM users

  1. [ConnectionHandler] SQL 파싱
  2. [TransactionManager] ReadView 생성/조회
     - RR: 첫 SELECT 시 생성, 이후 재사용
     - RC: 매번 새로 생성
  3. [BufferPool] 페이지 순회
  4. 각 행에 대해:
     a. createdTxId → ReadView 가시성 판단
     b. 보이면 → 결과에 포함
     c. 안 보이면 → undoLogPointer 따라 이전 버전 확인
     d. deletedTxId 있고 해당 delete가 "보이면" → 제외
  5. 가시적인 행만 반환 (Lock 없음!)
```

---

## 7. 구현 순서

### Step 1: 기반 인프라 (의존: 없음)
- TransactionManager + TransactionContext (txId 생성, 활성 트랜잭션 맵)
- Row 모델 확장 (createdTxId, deletedTxId, undoLogPointer)
- RowEncoder 확장 (MVCC 헤더 v2 포맷, v1 하위 호환)
- ConnectionHandler에 BEGIN/COMMIT/ROLLBACK 명령 추가
- Auto-commit 모드 지원

### Step 2: Undo Log (의존: Step 1)
- UndoLogManager (파일 형식, append, read)
- Rollback 구현 (Undo 체인 역추적)
- TableService 통합 (insert/delete에 Undo Log 기록 추가)

### Step 3: WAL / Redo Log (의존: Step 1, 2)
- WalManager (파일 형식, append, flush, LSN 관리)
- BufferPool 확장 (pageLsn, WAL flush 선행 보장)
- COMMIT 흐름 연결 (COMMIT 레코드 + flush)

### Step 4: Doublewrite Buffer (의존: Step 3)
- DoublewriteBuffer (순차 쓰기 + fsync + 랜덤 쓰기)
- Page checksum 도입
- BufferPool flush 시 DWB 경유

### Step 5: Crash Recovery (의존: Step 2, 3, 4)
- CrashRecoveryManager (Phase 0 → 1 → 2 → 3)
- 서버 시작 시 자동 복구

### Step 6: MVCC (의존: Step 1, 2)
- ReadView + 가시성 판단 알고리즘
- MVCC SELECT (Undo 체인 역추적으로 이전 버전 읽기)
- 격리 수준 (RC / RR)

### Step 7: Purge + 통합 테스트 (의존: Step 1~6)
- UndoPurgeService (불필요한 Undo Log 정리)
- 동시 트랜잭션 테스트
- 크래시 복구 시뮬레이션
- 격리 수준별 동작 검증

---

## 8. 새로 생성할 파일

| 파일 경로 | 설명 |
|-----------|------|
| `transaction/TransactionManager.kt` | 트랜잭션 생명주기, txId 생성, 활성 tx 추적 |
| `transaction/TransactionContext.kt` | 개별 트랜잭션 상태 |
| `transaction/IsolationLevel.kt` | 격리 수준 enum |
| `wal/WalManager.kt` | WAL 쓰기/읽기, LSN 관리, flush |
| `wal/WalRecord.kt` | WAL 레코드 데이터 클래스 |
| `wal/WalEncoder.kt` | WAL 바이너리 직렬화 |
| `undo/UndoLogManager.kt` | Undo Log 관리, rollback |
| `undo/UndoLogRecord.kt` | Undo 레코드 데이터 클래스 |
| `undo/UndoLogEncoder.kt` | Undo 바이너리 직렬화 |
| `undo/UndoPurgeService.kt` | Undo Log 가비지 컬렉션 |
| `mvcc/ReadView.kt` | MVCC 스냅샷, 가시성 판단 |
| `mvcc/MvccEngine.kt` | 행 가시성 + 버전 체인 탐색 |
| `recovery/CrashRecoveryManager.kt` | 크래시 복구 (Analysis→Redo→Undo) |
| `recovery/CheckpointManager.kt` | 주기적 체크포인트 |
| `storage/DoublewriteBuffer.kt` | Torn page 방지 이중 쓰기 |

## 9. 수정이 필요한 기존 파일

| 파일 | 변경 내용 |
|------|----------|
| `Row.kt` | createdTxId, deletedTxId, undoLogPointer 추가 |
| `RowEncoder.kt` | MVCC 헤더 인코딩 (v2 포맷) |
| `Page.kt` | pageLsn, checksum 필드 추가 |
| `BufferPool.kt` | WAL flush 선행, DWB 경유 flush, LSN 기반 dirty 추적 |
| `TableFileManager.kt` | 페이지 단위 쓰기, checksum 검증 |
| `TableService.kt` | TransactionManager 연동, Undo/WAL 기록 |
| `ConnectionHandler.kt` | BEGIN/COMMIT/ROLLBACK 처리, TransactionContext 보유 |
| `SqlParser.kt` | BEGIN, COMMIT, ROLLBACK 파싱 |
| `DbTcpServer.kt` | 시작 시 CrashRecoveryManager.recover() 호출 |
