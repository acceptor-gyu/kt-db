# 동시성 제어 (Concurrency Control)

## 개요

여러 클라이언트가 동시에 같은 데이터에 접근할 때 데이터 일관성을 보장하는 것은 데이터베이스의 핵심 책임입니다. 이 문서에서는 동시성 문제의 본질과 해결 방법, 그리고 MySQL에서의 실무 적용 사례를 다룹니다.

---

## 1. 동시성 문제의 이해

### 핵심 개념: Race Condition (경쟁 조건)

**Race Condition**은 두 개 이상의 스레드가 공유 데이터를 동시에 수정할 때 실행 순서에 따라 결과가 달라지는 현상입니다.

**왜 발생하는가?**
```kotlin
count++  // 실제로는 3단계로 나뉨
// 1. 메모리에서 count 값 읽기
// 2. count에 1을 더하기
// 3. 결과를 메모리에 쓰기
```

**문제 시나리오**:
```
Thread 1: count = 5 읽음
Thread 2: count = 5 읽음  ← 아직 Thread 1이 쓰기 전!
Thread 1: 6 쓰기
Thread 2: 6 쓰기  ← 예상: 7, 실제: 6 ❌
```

### Lost Update (갱신 손실)

**Lost Update**는 한 트랜잭션의 수정 내용이 다른 트랜잭션에 의해 덮어씌워지는 현상입니다.

**예시**:
```
Thread 1: 테이블 읽기 (행 개수: 100)
Thread 2: 테이블 읽기 (행 개수: 100)
Thread 1: 새 행 추가 (101번째)
Thread 2: 새 행 추가 (101번째, 덮어씀) ❌
```

### MySQL의 동시성 문제 예시

```sql
-- Session 1
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 1000원

-- Session 2
START TRANSACTION;
SELECT balance FROM accounts WHERE id = 1;  -- 1000원

-- Session 1
UPDATE accounts SET balance = balance - 100 WHERE id = 1;  -- 900원
COMMIT;

-- Session 2
UPDATE accounts SET balance = balance - 200 WHERE id = 1;  -- 800원 (예상: 700원!)
COMMIT;
```

**MySQL의 해결책**:
- **Row-level Locking**: 행 단위로 락을 걸어 동시 수정 방지
- **Transaction Isolation**: 격리 수준으로 동시성 제어

---

## 2. ConcurrentHashMap

### 핵심 개념: 세그먼트 기반 락

**ConcurrentHashMap**은 전체 맵에 하나의 락을 거는 대신, 내부를 여러 버킷으로 나누어 각각 독립적으로 락을 관리합니다.

**왜 사용하는가?**
- 일반 `HashMap`은 멀티스레드 환경에서 안전하지 않음
- `Collections.synchronizedMap()`은 전체에 락을 걸어 성능 저하
- `ConcurrentHashMap`은 버킷 단위 락으로 동시성 극대화

### 구현 코드 (핵심만)

**파일**: `TableService.kt:21-47`

```kotlin
class TableService {
    private val tables = ConcurrentHashMap<String, Table>()  // Thread-safe

    fun createTable(request: CreateTableRequest): Table {
        val table = Table(/* ... */)

        // putIfAbsent: 원자적으로 "확인 후 삽입"
        val existing = tables.putIfAbsent(request.tableName, table)

        if (existing != null) {
            throw IllegalArgumentException("Table already exists")
        }
        return table
    }

    fun getTable(name: String): Table? {
        return tables[name]  // 스레드 안전한 읽기
    }
}
```

### 주요 개념 설명

#### 1) ConcurrentHashMap vs HashMap

| 특성 | HashMap | ConcurrentHashMap |
|------|---------|-------------------|
| 스레드 안전성 | ❌ 없음 | ✅ 있음 |
| 동시 읽기 | 가능 (위험) | 안전하게 가능 |
| 동시 쓰기 | 데이터 손상 가능 | 안전하게 가능 |
| 성능 | 단일 스레드 빠름 | 멀티 스레드 빠름 |
| Null 키/값 | 허용 | 불허 |

#### 2) 내부 동작 원리

```
ConcurrentHashMap (Java 8+)
┌─────────────────────────────────┐
│ Bucket 0 │ Bucket 1 │ Bucket 2 │  ← 각 버킷마다 독립적 락
│  (Lock)  │  (Lock)  │  (Lock)  │
└─────────────────────────────────┘
→ Thread 1이 Bucket 0 수정 중
→ Thread 2는 Bucket 1 동시 수정 가능 ✅

synchronized HashMap
┌─────────────────────────────────┐
│        전체 테이블              │
│      (Global Lock)              │  ← 전체에 하나의 락
└─────────────────────────────────┘
→ Thread 1이 수정 중이면
→ Thread 2는 모든 작업 대기 ❌
```

**ConcurrentHashMap의 장점**:
- 읽기는 대부분 락 없이 수행 (Lock-free reads)
- 쓰기는 해당 버킷만 락 (Fine-grained locking)
- `get()` 연산은 거의 락 없이 수행

### MySQL의 테이블 락

**MySQL도 유사한 락 전략을 사용합니다**:

```sql
-- 테이블 전체 락 (비효율적)
LOCK TABLES users WRITE;
UPDATE users SET status = 'active' WHERE id = 1;
UNLOCK TABLES;

-- InnoDB의 Row-level Lock (효율적)
START TRANSACTION;
SELECT * FROM users WHERE id = 1 FOR UPDATE;  -- 행 1번만 락
UPDATE users SET status = 'active' WHERE id = 1;
COMMIT;

-- 락 정보 확인
SELECT * FROM performance_schema.data_locks;
```

**InnoDB의 락 종류**:
- **Row Lock**: 행 단위 락 (ConcurrentHashMap의 버킷 락과 유사)
- **Table Lock**: 테이블 전체 락
- **Gap Lock**: 인덱스 범위 락 (Phantom Read 방지)

---

## 3. AtomicLong - Lock-free 연산

### 핵심 개념: CAS (Compare-And-Swap)

**AtomicLong**은 `long` 타입 변수에 대한 원자적 연산을 제공합니다. 락 없이 **CAS 알고리즘**을 사용하여 높은 성능을 보장합니다.

**CAS란?**
```
1. 현재 값 읽기 (expected)
2. 새 값 계산 (new)
3. 메모리 값이 여전히 expected이면 new로 변경
4. 변경 실패 시 재시도
```

### 구현 코드 (핵심만)

**파일**: `ConnectionManager.kt:34-45`

```kotlin
class ConnectionManager {
    private val connections = ConcurrentHashMap<Long, ConnectionHandler>()
    private val connectionIdGenerator = AtomicLong(0)  // Thread-safe ID 생성

    fun generateConnectionId(): Long {
        return connectionIdGenerator.incrementAndGet()  // 원자적 증가
    }

    // ...(register, unregister 등)
}
```

### 주요 개념 설명

#### 1) 왜 AtomicLong을 사용하는가?

**❌ 잘못된 방법 (Race Condition 발생)**:
```kotlin
var counter = 0L

fun generateId(): Long {
    counter++  // 원자적이지 않음!
    return counter
}

// Thread 1: counter = 5 읽음
// Thread 2: counter = 5 읽음  ← 중복!
// Thread 1: counter = 6 쓰기
// Thread 2: counter = 6 쓰기  ← 중복 ID 생성! ❌
```

**✅ 올바른 방법 (AtomicLong 사용)**:
```kotlin
val counter = AtomicLong(0)

fun generateId(): Long {
    return counter.incrementAndGet()  // 원자적 연산
}

// Thread 1: CAS로 5 → 6 변경, 6 반환
// Thread 2: CAS로 6 → 7 변경, 7 반환  ← 고유 ID ✅
```

#### 2) CAS 알고리즘의 내부 동작

```kotlin
// AtomicLong.incrementAndGet()의 의사코드
fun incrementAndGet(): Long {
    while (true) {
        val current = value                    // 1. 현재 값 읽기
        val next = current + 1                 // 2. 새 값 계산

        // 3. CAS: "현재 값이 여전히 current이면 next로 변경"
        if (compareAndSwap(current, next)) {
            return next                        // 성공
        }
        // 실패: 다른 스레드가 값을 변경함 → 재시도
    }
}
```

**CAS의 장점**:
- 락 불필요 → 데드락 없음
- 충돌이 적으면 매우 빠름
- 컨텍스트 스위칭 최소화

**CAS의 단점**:
- 충돌이 많으면 재시도 비용 증가
- ABA 문제 가능성 (값이 A → B → A로 변경되는 경우)

### MySQL의 Auto Increment

**MySQL도 유사한 메커니즘을 사용합니다**:

```sql
-- Auto Increment 컬럼 정의
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100)
);

-- 자동 증가 (내부적으로 원자적 연산)
INSERT INTO users (name) VALUES ('Alice');  -- id = 1
INSERT INTO users (name) VALUES ('Bob');    -- id = 2

-- 현재 Auto Increment 값 확인
SELECT AUTO_INCREMENT FROM information_schema.TABLES
WHERE TABLE_NAME = 'users';

-- InnoDB는 테이블 레벨 카운터를 원자적으로 증가
-- 여러 세션이 동시에 INSERT해도 고유 ID 보장
```

**MySQL의 Auto Increment 락 모드**:
```sql
-- innodb_autoinc_lock_mode 설정
-- 0: Traditional (테이블 락)
-- 1: Consecutive (연속 모드, 기본값)
-- 2: Interleaved (Lock-free, 가장 빠름)
SHOW VARIABLES LIKE 'innodb_autoinc_lock_mode';
```

---

## 4. compute() - 원자적 읽기-수정-쓰기

### 핵심 개념: Atomic Read-Modify-Write

**compute()**는 키의 값을 읽고 수정하는 작업을 **원자적으로** 수행합니다.

**왜 필요한가?**
- `get()` → 수정 → `put()` 패턴은 원자적이지 않음
- 여러 스레드가 동시에 수정하면 데이터 손실 발생

### 구현 코드 (핵심만)

**파일**: `TableService.kt:68-79`

```kotlin
fun insert(tableName: String, values: Map<String, String>): Boolean {
    // compute: 읽기-수정-쓰기를 원자적으로 수행
    val result = tables.compute(tableName) { _, existingTable ->
        existingTable?.copy(value = existingTable.value + values)
    }

    return result != null  // 테이블이 존재했으면 성공
}
```

### 주요 개념 설명

#### 1) compute() vs get() + put()

**❌ 잘못된 방법 (Race Condition 발생)**:
```kotlin
fun insertUnsafe(tableName: String, row: Map<String, Any>) {
    val table = tables[tableName]              // 1. 읽기
    if (table != null) {
        val updated = table.copy(rows = table.rows + row)  // 2. 수정
        tables[tableName] = updated            // 3. 쓰기
        // ❌ 1-2-3 사이에 다른 스레드가 개입 가능!
    }
}

// Thread 1: table 읽기 (rows = [A, B])
// Thread 2: table 읽기 (rows = [A, B])
// Thread 1: rows = [A, B, C]로 쓰기
// Thread 2: rows = [A, B, D]로 쓰기  ← C가 사라짐! ❌
```

**✅ 올바른 방법 (compute 사용)**:
```kotlin
fun insertSafe(tableName: String, row: Map<String, Any>) {
    tables.compute(tableName) { _, existingTable ->
        existingTable?.copy(rows = existingTable.rows + row)
    }
    // ✅ compute 내부에서 읽기-수정-쓰기가 원자적으로 수행됨
}
```

#### 2) compute()의 동작 원리

```
compute(key, function):
1. 해당 버킷에 락 획득
2. key의 현재 값 읽기
3. function 실행 (새 값 계산)
4. 새 값으로 업데이트
5. 락 해제
→ 1-5가 원자적으로 수행됨
```

### MySQL의 원자적 업데이트

**MySQL도 원자적 업데이트를 지원합니다**:

```sql
-- ❌ 비원자적 방법 (Race Condition 가능)
SELECT @count := count FROM stats WHERE id = 1;
UPDATE stats SET count = @count + 1 WHERE id = 1;

-- ✅ 원자적 방법 (compute()와 유사)
UPDATE stats SET count = count + 1 WHERE id = 1;

-- InnoDB는 내부적으로:
-- 1. 행 락 획득
-- 2. 현재 값 읽기
-- 3. 새 값 계산
-- 4. 업데이트
-- 5. 락 해제
```

**트랜잭션과 함께 사용**:
```sql
START TRANSACTION;

-- FOR UPDATE: 행 락 획득 (다른 세션 대기)
SELECT balance FROM accounts WHERE id = 1 FOR UPDATE;

-- 원자적 업데이트
UPDATE accounts SET balance = balance - 100 WHERE id = 1;

COMMIT;
```

---

## 5. 스레드 풀 (Thread Pool)

### 핵심 개념: 스레드 재사용

**Thread Pool**은 미리 생성한 스레드를 재사용하여 생성/소멸 비용을 절감하는 패턴입니다.

**왜 사용하는가?**
- 스레드 생성은 비용이 큼 (메모리 할당, 커널 호출)
- 무제한 스레드 생성 시 메모리 부족 (OOM)
- 스레드 수 제어로 리소스 관리

### 구현 코드 (핵심만)

**파일**: `DbTcpServer.kt:37-65`

```kotlin
class DbTcpServer(private val port: Int, private val maxConnections: Int = 10) {
    private val executor = Executors.newFixedThreadPool(maxConnections)

    fun start() {
        val serverSocket = ServerSocket(port)

        while (running) {
            val clientSocket = serverSocket.accept()

            // 스레드 풀에 작업 제출
            executor.submit {
                ConnectionHandler(/* ... */).run()
            }
        }
    }

    // ...(graceful shutdown)
}
```

### 주요 개념 설명

#### 1) 스레드 풀의 장점

**장점**:
1. **스레드 재사용**: 생성/소멸 비용 절감
2. **리소스 제어**: 최대 스레드 수 제한
3. **작업 큐잉**: 스레드보다 많은 작업 대기 가능

**비용 비교**:
```
스레드 생성 비용: 1ms ~ 100ms (OS 종속)
스레드 풀 재사용: 거의 0ms

1000개 요청 처리 시:
- Thread-per-request: 1000ms ~ 100,000ms
- Thread Pool (10개): 거의 0ms (대기 시간 제외)
```

#### 2) 스레드 풀 종류

```kotlin
// 1. 고정 크기 (Fixed Thread Pool)
val fixed = Executors.newFixedThreadPool(10)
// 장점: 리소스 예측 가능
// 단점: 동적 확장 불가

// 2. 캐시 (Cached Thread Pool)
val cached = Executors.newCachedThreadPool()
// 장점: 필요시 스레드 생성/제거
// 단점: 무한정 증가 가능 (위험)

// 3. 단일 스레드
val single = Executors.newSingleThreadExecutor()
// 장점: 순서 보장
// 단점: 병렬 처리 불가
```

### MySQL의 스레드 관리

**MySQL의 스레드 모델**:

```sql
-- 최대 연결 수 설정
SHOW VARIABLES LIKE 'max_connections';  -- 기본값: 151
SET GLOBAL max_connections = 300;

-- 현재 연결 수 확인
SHOW STATUS LIKE 'Threads_connected';

-- 실행 중인 스레드
SHOW PROCESSLIST;
```

**MySQL의 스레드 전략**:

| MySQL 버전 | 기본 전략 | 설명 |
|-----------|----------|------|
| MySQL 5.6 이전 | Thread-per-connection | 연결당 1개 스레드 |
| MySQL 5.7+ | Thread Pool (플러그인) | 스레드 재사용 |
| MySQL 8.0+ | Thread Pool (개선) | CPU 코어 수 기반 |

**Thread Pool 설정**:
```sql
-- Thread Pool 플러그인 활성화 (Enterprise)
INSTALL PLUGIN thread_pool SONAME 'thread_pool.so';

-- 스레드 풀 크기 (CPU 코어 수)
SHOW VARIABLES LIKE 'thread_pool_size';

-- 우선순위 큐 설정
SHOW VARIABLES LIKE 'thread_pool_high_priority_connection';
```

---

## 6. 동시성 제어 전략 비교

### 낙관적 락킹 vs 비관적 락킹

| 특성 | Optimistic (낙관적) | Pessimistic (비관적) |
|-----|-------------------|-------------------|
| 가정 | 충돌이 드묾 | 충돌이 빈번 |
| 방식 | CAS, 버전 체크 | Lock 획득 |
| 성능 | 충돌 적을 때 빠름 | 충돌 많을 때 안정 |
| 재시도 | 필요 (충돌 시) | 불필요 |
| 데드락 | 불가능 | 가능 |

**코드 예시**:
```kotlin
// 낙관적: CAS 사용
val counter = AtomicLong(0)
counter.incrementAndGet()  // 충돌 시 재시도

// 비관적: Lock 사용
val lock = ReentrantLock()
lock.lock()
try {
    counter++  // 락으로 보호
} finally {
    lock.unlock()
}
```

### MySQL의 락킹 전략

**낙관적 락킹 (Optimistic Locking)**:
```sql
-- 버전 컬럼 사용
CREATE TABLE products (
    id INT PRIMARY KEY,
    name VARCHAR(100),
    version INT DEFAULT 0
);

-- 업데이트 시 버전 체크
UPDATE products
SET name = 'New Name', version = version + 1
WHERE id = 1 AND version = 5;  -- 버전이 맞을 때만 업데이트

-- 영향받은 행 수가 0이면 충돌 발생 → 재시도
```

**비관적 락킹 (Pessimistic Locking)**:
```sql
START TRANSACTION;

-- FOR UPDATE: 행 락 획득 (다른 세션 대기)
SELECT * FROM products WHERE id = 1 FOR UPDATE;

-- 안전하게 업데이트
UPDATE products SET name = 'New Name' WHERE id = 1;

COMMIT;
```

---

## 실무 활용 정리

### MySQL의 동시성 제어 메커니즘

**InnoDB 스토리지 엔진**:
```sql
-- 격리 수준 확인
SHOW VARIABLES LIKE 'transaction_isolation';

-- 격리 수준 설정
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
```

**격리 수준 비교**:

| 격리 수준 | Dirty Read | Non-Repeatable Read | Phantom Read | 성능 |
|---------|-----------|---------------------|--------------|------|
| READ UNCOMMITTED | ✅ | ✅ | ✅ | 가장 빠름 |
| READ COMMITTED | ❌ | ✅ | ✅ | 빠름 |
| REPEATABLE READ | ❌ | ❌ | ✅ (InnoDB는 ❌) | 보통 |
| SERIALIZABLE | ❌ | ❌ | ❌ | 가장 느림 |

**락 모니터링**:
```sql
-- 현재 락 정보
SELECT * FROM performance_schema.data_locks;

-- 락 대기 정보
SELECT * FROM performance_schema.data_lock_waits;

-- 데드락 감지
SHOW ENGINE INNODB STATUS;
```

### 성능 최적화 팁

**1. 락 범위 최소화**:
```kotlin
// ❌ 전체 메서드에 락
@Synchronized
fun process(data: List<String>) {
    val processed = data.map { heavyComputation(it) }  // 락 불필요
    criticalSection()  // 락 필요
}

// ✅ 필요한 부분만 락
fun process(data: List<String>) {
    val processed = data.map { heavyComputation(it) }
    synchronized(this) { criticalSection() }
}
```

**2. ConcurrentHashMap 활용**:
```kotlin
// ❌ synchronized + HashMap
@Synchronized
fun increment(key: String) {
    map[key] = (map[key] ?: 0) + 1
}

// ✅ ConcurrentHashMap
fun increment(key: String) {
    map.computeIfAbsent(key) { AtomicInteger(0) }
        .incrementAndGet()
}
```

---

## 참고 자료

- [Java Concurrency in Practice](https://jcip.net/)
- [ConcurrentHashMap 내부 구조](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ConcurrentHashMap.html)
- [MySQL InnoDB Locking](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking.html)
- [MySQL Transaction Isolation](https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html)
