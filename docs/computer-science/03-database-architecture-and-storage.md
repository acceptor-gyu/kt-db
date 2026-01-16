# 데이터베이스 아키텍처 & 물리 구조

## 개요

데이터베이스의 성능은 데이터를 **어떻게 저장하고 읽어오는가**에 크게 좌우됩니다. 이 문서에서는 페이지 기반 저장소, 디스크 I/O 최적화, 그리고 데이터 직렬화에 대해 다룹니다.

---

## 1. 데이터베이스 아키텍처 개요

### 핵심 개념: 계층 구조

데이터베이스는 여러 계층으로 나뉘어 각자의 역할을 수행합니다.

```
┌─────────────────────────────────────────┐
│      네트워크 계층 (Network Layer)      │  ← TCP/IP 서버, 연결 관리
├─────────────────────────────────────────┤
│    쿼리 처리 계층 (Query Processing)    │  ← Parser, Optimizer, Executor
├─────────────────────────────────────────┤
│     저장소 엔진 (Storage Engine)        │  ← Page Manager, Buffer Pool
├─────────────────────────────────────────┤
│      파일 시스템 (File System)          │  ← 디스크 I/O, 파일 관리
└─────────────────────────────────────────┘
```

**저장소 엔진의 역할**:
1. **데이터 영속성 (Persistence)**: 메모리 → 디스크 저장
2. **효율적인 I/O**: 디스크 접근 횟수 최소화
3. **데이터 압축 & 인코딩**: 저장 공간 절약

### MySQL의 아키텍처

**MySQL의 계층 구조**:
```sql
-- MySQL의 스토리지 엔진 확인
SHOW ENGINES;

-- 스토리지 엔진별 특징
-- InnoDB: 트랜잭션, 외래키, Row-level Lock
-- MyISAM: 전체 테이블 락, 단순하고 빠름
-- Memory: 인메모리 저장 (재시작 시 데이터 손실)
```

**스토리지 엔진 선택**:
```sql
-- 테이블 생성 시 엔진 지정
CREATE TABLE users (
    id INT PRIMARY KEY,
    name VARCHAR(100)
) ENGINE=InnoDB;  -- 기본값 (MySQL 5.5+)

-- 기존 테이블 엔진 변경
ALTER TABLE users ENGINE=MyISAM;
```

---

## 2. 페이지 기반 저장소 (Page-Based Storage)

### 핵심 개념: 페이지 (Page)

**페이지**는 디스크 I/O의 최소 단위입니다. 1바이트만 필요해도 전체 페이지를 읽어야 합니다.

**왜 페이지 단위로 관리하는가?**
- 디스크는 블록 단위로 읽기/쓰기를 수행 (보통 4KB-8KB)
- 1바이트를 읽어도 전체 블록을 읽어야 함
- 여러 행을 한 페이지에 모아 I/O 효율 극대화

### 구현 코드 (핵심만)

**파일**: `Page.kt:11-26`

```kotlin
data class Page(
    val id: Int,           // 페이지 ID (0부터 시작)
    val data: ByteArray    // 실제 데이터 (PAGE_SIZE 바이트)
) {
    // ...(equals, hashCode)
}
```

**상수 정의**:
```kotlin
const val PAGE_SIZE = 16          // 한 페이지의 크기 (Byte)
const val INT_SIZE = 4            // INT 타입의 크기 (Byte)
const val DEFAULT_PAGE_COUNT = 6  // 기본 페이지 개수
```

### 주요 개념 설명

#### 1) 실제 데이터베이스의 페이지 크기

| 데이터베이스 | 페이지 크기 | 이유 |
|------------|-----------|------|
| **InnoDB (MySQL)** | 16KB | OLTP 워크로드에 최적화 |
| **PostgreSQL** | 8KB | 작은 페이지로 캐시 효율 향상 |
| **SQL Server** | 8KB | 메모리 효율성 |
| **이 프로젝트** | 16B | 학습용 축소 크기 |

**페이지 크기가 성능에 미치는 영향**:
```
작은 페이지 (4KB):
- 장점: 메모리 효율적, 캐시 히트율 높음
- 단점: I/O 횟수 증가

큰 페이지 (16KB-64KB):
- 장점: I/O 횟수 감소, 순차 읽기 빠름
- 단점: 메모리 낭비 가능
```

#### 2) InnoDB 페이지 구조

```
InnoDB 페이지 (16KB)
┌──────────────────────────────────────┐
│        File Header (38 bytes)        │  ← 체크섬, LSN, 페이지 타입
├──────────────────────────────────────┤
│        Page Header (56 bytes)        │  ← 레코드 개수, 힙 포인터
├──────────────────────────────────────┤
│         User Records (가변)          │  ← 실제 데이터 행들
├──────────────────────────────────────┤
│         Page Directory (가변)        │  ← 레코드 위치 인덱스
├──────────────────────────────────────┤
│        File Trailer (8 bytes)        │  ← 체크섬 검증
└──────────────────────────────────────┘
```

**주요 필드**:
- **File Header**: 체크섬, 페이지 번호, 이전/다음 페이지 포인터
- **Page Header**: 레코드 개수, 힙 위치, 삭제된 레코드 수
- **User Records**: 실제 행 데이터 (가변 길이)
- **Page Directory**: 빠른 검색을 위한 슬롯 배열

### MySQL의 페이지 관리

```sql
-- InnoDB 페이지 크기 확인 (MySQL 5.7+)
SHOW VARIABLES LIKE 'innodb_page_size';  -- 기본값: 16384 (16KB)

-- 테이블 생성 시 페이지 크기 지정 (MySQL 5.7+)
CREATE TABLE large_table (...) ROW_FORMAT=COMPRESSED KEY_BLOCK_SIZE=8;

-- 페이지 압축 활성화 (MySQL 5.7+)
CREATE TABLE compressed_table (...)
COMPRESSION='zlib';  -- 페이지 압축 (디스크 공간 절약)
```

**페이지 관련 통계**:
```sql
-- 테이블이 사용하는 페이지 수 확인
SELECT
    table_name,
    data_length / 16384 AS pages_used,
    index_length / 16384 AS index_pages
FROM information_schema.TABLES
WHERE table_schema = 'mydb';
```

---

## 3. 페이지 매니저 (Page Manager)

### 핵심 개념: 버퍼 풀 (Buffer Pool)

**페이지 매니저**는 디스크와 메모리 간의 데이터 이동을 관리합니다. 버퍼 풀의 단순한 형태로, 페이지를 메모리에 캐싱하여 디스크 I/O를 줄입니다.

**왜 필요한가?**
- 디스크 I/O는 메모리 접근보다 10,000배 이상 느림
- 자주 사용하는 페이지를 메모리에 캐싱
- Cache Hit 비율을 높여 성능 향상

### 구현 코드 (핵심만)

**파일**: `PageManager.kt:15-55`

```kotlin
class PageManager(private val file: RandomAccessFile) {
    private val pages: MutableList<Page> = mutableListOf()

    // 특정 페이지 읽기 (Cache Hit)
    fun readAt(id: Int): ByteArray {
        require(id in pages.indices) { "Page $id out of bounds" }
        return pages[id].data
    }

    // 파일 전체를 페이지 단위로 읽기 (Sequential I/O)
    fun readAll() {
        pages.clear()
        val pageCount = ((file.length() + PAGE_SIZE - 1) / PAGE_SIZE).toInt()

        for (i in 0 until pageCount) {
            val offset = i * PAGE_SIZE
            val buf = ByteArray(PAGE_SIZE)

            file.seek(offset.toLong())  // 파일 포인터 이동
            file.read(buf, 0, PAGE_SIZE)

            pages.add(Page(id = i, data = buf))
        }
    }

    // ...(pageCount, isLoaded)
}
```

### 주요 개념 설명

#### 1) RandomAccessFile의 seek()

**seek()**는 파일의 특정 위치로 포인터를 이동합니다.

```kotlin
// O(1) 시간 복잡도로 특정 위치 이동
file.seek(pageId * PAGE_SIZE)
file.read(buffer)

// Sequential Read: seek() 없이 연속 읽기
while (file.read(buffer) != -1) {
    // ...(처리)
}
```

**왜 사용하는가?**
- 특정 페이지에 직접 접근 가능 (Random Access)
- 순차 읽기와 랜덤 읽기 모두 지원
- 대용량 파일에서 필요한 부분만 읽기 가능

#### 2) 메모리 캐싱의 효과

```
디스크 접근:
- HDD: 5-10ms (Seek Time + Rotation Delay)
- SSD: 0.1-0.5ms

메모리 접근:
- RAM: 0.0001ms (100 나노초)

→ 메모리가 디스크보다 10,000배 ~ 100,000배 빠름!
```

### MySQL의 버퍼 풀

**InnoDB 버퍼 풀**은 페이지 매니저의 실전 버전입니다.

```sql
-- 버퍼 풀 크기 확인
SHOW VARIABLES LIKE 'innodb_buffer_pool_size';

-- 버퍼 풀 크기 설정 (my.cnf)
innodb_buffer_pool_size = 8G  -- 전체 RAM의 50-75% 권장

-- 버퍼 풀 통계 확인
SHOW STATUS LIKE 'Innodb_buffer_pool%';

-- 주요 메트릭
-- Innodb_buffer_pool_read_requests: 총 읽기 요청
-- Innodb_buffer_pool_reads: 디스크에서 읽은 횟수
-- Cache Hit Ratio = (requests - reads) / requests * 100
```

**버퍼 풀 히트율 계산**:
```sql
SELECT
    (1 - (Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests)) * 100
    AS hit_ratio
FROM (
    SELECT
        variable_value AS Innodb_buffer_pool_reads
    FROM performance_schema.global_status
    WHERE variable_name = 'Innodb_buffer_pool_reads'
) AS reads,
(
    SELECT
        variable_value AS Innodb_buffer_pool_read_requests
    FROM performance_schema.global_status
    WHERE variable_name = 'Innodb_buffer_pool_read_requests'
) AS requests;

-- 히트율이 99% 이상이면 버퍼 풀 크기가 적절함
```

**버퍼 풀 인스턴스**:
```sql
-- 버퍼 풀을 여러 인스턴스로 분할 (동시성 향상)
innodb_buffer_pool_instances = 8  -- 1GB 이상일 때 권장

-- 각 인스턴스는 독립적으로 관리됨 (락 경합 감소)
```

---

## 4. 디스크 I/O 최적화

### 핵심 개념: Sequential I/O vs Random I/O

디스크 I/O 패턴이 성능에 미치는 영향은 매우 큽니다.

**Sequential I/O (순차 읽기)**:
```kotlin
// 연속된 페이지를 순서대로 읽기
for (i in 0 until pageCount) {
    file.seek(i * PAGE_SIZE)
    file.read(buffer)
}
// 디스크 헤드가 연속된 위치를 읽음 → 빠름!
```

**Random I/O (랜덤 읽기)**:
```kotlin
// 무작위 순서로 페이지 읽기
val randomPages = listOf(100, 5, 87, 23, ...)
for (pageId in randomPages) {
    file.seek(pageId * PAGE_SIZE)
    file.read(buffer)
}
// 디스크 헤드가 왔다갔다 → 느림!
```

### 주요 개념 설명

#### 1) 성능 차이 비교

| I/O 타입 | HDD | SSD | 차이 |
|---------|-----|-----|------|
| Sequential Read | **100-200 MB/s** | 500-3000 MB/s | HDD 1배 |
| Random Read | 1-10 MB/s | 200-500 MB/s | HDD 10-100배 느림! |

**왜 HDD에서 차이가 큰가?**
```
HDD (기계식):
- Seek Time: 디스크 헤드 이동 시간 (5-10ms)
- Rotation Delay: 디스크 회전 대기 (2-5ms)
- Random I/O: 매번 Seek + Rotation 발생

SSD (전자식):
- Seek Time 없음 (전자 신호로 접근)
- Random I/O도 비교적 빠름
```

#### 2) B-tree 인덱스의 I/O 최적화

**B-tree는 디스크 I/O를 최소화하는 자료구조**입니다.

```
B-tree 깊이 3 예시 (1억 행):
┌──────────┐
│   Root   │  ← 1번 I/O
└──────────┘
     │
┌────┴────┐
│ Internal│  ← 2번 I/O
└─────────┘
     │
┌────┴────┐
│  Leaf   │  ← 3번 I/O
└─────────┘

→ 1억 행 중 1개 검색: 3번 I/O만 필요!
```

**왜 효율적인가?**
- 각 노드가 수백~수천 개의 키를 저장
- 깊이가 낮아 I/O 횟수 최소화
- 범위 검색 시 리프 노드만 순차 읽기

### MySQL의 I/O 최적화 기법

**1. Prefetching (선행 읽기)**:
```sql
-- InnoDB는 Sequential Scan 시 여러 페이지를 미리 읽음
SHOW VARIABLES LIKE 'innodb_read_ahead_threshold';  -- 기본값: 56

-- Linear Read-Ahead: 순차 접근 감지 시 여러 페이지 미리 읽기
-- Random Read-Ahead: 버퍼 풀에 연속 페이지가 있으면 나머지 읽기
```

**2. Write-Ahead Logging (WAL)**:
```
트랜잭션 커밋 과정:
1. 변경 사항을 WAL(Redo Log)에 순차 기록 ← Sequential I/O (빠름!)
2. 트랜잭션 커밋 완료
3. 나중에 데이터 페이지를 디스크에 쓰기 ← Random I/O (느림)

장점:
- 커밋이 빠름 (순차 I/O만 필요)
- 장애 복구 가능 (WAL 재생)
- 데이터 페이지는 배치로 쓰기 (효율적)
```

**WAL 설정**:
```sql
-- Redo Log 크기 설정
SHOW VARIABLES LIKE 'innodb_log_file_size';  -- 기본값: 48MB

-- Redo Log 파일 개수
SHOW VARIABLES LIKE 'innodb_log_files_in_group';  -- 기본값: 2

-- 총 Redo Log 크기 = log_file_size * log_files_in_group
-- 권장: 1-2시간 분량의 쓰기 워크로드
```

**3. Double Write Buffer**:
```sql
-- 부분 쓰기 방지 메커니즘
SHOW VARIABLES LIKE 'innodb_doublewrite';  -- 기본값: ON

-- 동작 원리:
-- 1. 페이지를 Double Write Buffer에 순차 쓰기
-- 2. Double Write Buffer를 디스크에 플러시
-- 3. 실제 데이터 파일에 페이지 쓰기
-- → 장애 시 Double Write Buffer에서 복구
```

**4. I/O 통계 확인**:
```sql
-- 파일 I/O 통계
SELECT * FROM performance_schema.file_summary_by_instance
WHERE file_name LIKE '%.ibd'
ORDER BY sum_number_of_bytes_read DESC
LIMIT 10;

-- 테이블별 I/O 통계
SELECT
    object_schema,
    object_name,
    count_read,
    count_write,
    sum_timer_wait / 1000000000000 AS total_latency_sec
FROM performance_schema.table_io_waits_summary_by_table
ORDER BY sum_timer_wait DESC
LIMIT 10;
```

---

## 5. 데이터 직렬화 (Serialization)

### 핵심 개념: 메모리 ↔ 디스크 변환

**직렬화**는 메모리의 데이터 구조를 바이트 배열로 변환하는 과정입니다.

**왜 필요한가?**
- 디스크와 네트워크는 바이트만 전송 가능
- 객체를 바이트로 변환해야 저장/전송 가능
- 역직렬화로 다시 객체로 복원

### 구현 코드 (핵심만)

**파일**: `IntFieldEncoder.kt:15-29`

```kotlin
class IntFieldEncoder {
    // INT 값을 4바이트로 직렬화
    fun encode(value: Int): ByteArray {
        return ByteBuffer
            .allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array()
    }

    // 4바이트를 INT로 역직렬화
    fun decode(bytes: ByteArray): Int {
        return ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)
            .int
    }
}
```

**행 인코더**:
```kotlin
class RowEncoder(private val intFieldEncoder: IntFieldEncoder) {
    fun encodeRow(values: IntArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 4)
        for (value in values) {
            buffer.put(intFieldEncoder.encode(value))
        }
        return buffer.array()
    }

    // ...(decodeRow)
}
```

### 주요 개념 설명

#### 1) Big Endian vs Little Endian

**Endian**은 바이트 순서를 결정합니다.

```
값: 0x12345678 (305,419,896)

Big Endian (네트워크 바이트 순서):
  메모리: [12] [34] [56] [78]
  가장 큰 바이트가 앞에 (사람이 읽는 순서)

Little Endian (Intel x86):
  메모리: [78] [56] [34] [12]
  가장 작은 바이트가 앞에 (CPU 효율)
```

**왜 Big Endian을 사용하는가?**
- 네트워크 프로토콜의 표준 (RFC 1700)
- 플랫폼 간 호환성 보장
- 디버깅 시 사람이 읽기 쉬움

#### 2) ByteBuffer의 동작 원리

```kotlin
val buffer = ByteBuffer.allocate(12)

// 쓰기
buffer.putInt(100)  // position: 0 → 4
buffer.putInt(200)  // position: 4 → 8
buffer.putInt(300)  // position: 8 → 12

// 읽기 모드로 전환
buffer.flip()       // position: 12 → 0, limit: 12

// 읽기
buffer.getInt()     // 100, position: 0 → 4
buffer.getInt()     // 200, position: 4 → 8
```

**Buffer의 세 가지 속성**:
```
┌─────────────────────────────────┐
│  0  │  1  │  2  │  3  │  4  │ 5 │
└─────────────────────────────────┘
  ↑                       ↑       ↑
position              limit   capacity
```

- **capacity**: 버퍼 크기 (변경 불가)
- **limit**: 읽기/쓰기 가능한 끝
- **position**: 현재 위치

### MySQL의 행 형식 (Row Format)

**InnoDB는 여러 행 형식을 지원합니다**:

```sql
-- 행 형식 확인
SELECT
    table_name,
    row_format
FROM information_schema.tables
WHERE table_schema = 'mydb';

-- 행 형식 종류
-- COMPACT: 기본값 (MySQL 5.0+), 20% 공간 절약
-- DYNAMIC: 가변 길이 컬럼 최적화 (MySQL 5.7+ 기본값)
-- COMPRESSED: 압축 (zlib), 50% 공간 절약
-- REDUNDANT: 구형 (MySQL 4.1)
```

**행 형식 지정**:
```sql
-- 테이블 생성 시 지정
CREATE TABLE users (
    id INT,
    name VARCHAR(100)
) ROW_FORMAT=DYNAMIC;

-- 기존 테이블 변경
ALTER TABLE users ROW_FORMAT=COMPRESSED;
```

**DYNAMIC vs COMPACT 비교**:
```
COMPACT:
- VARCHAR(255): 최대 255바이트 인라인 저장
- TEXT, BLOB: 768바이트까지 인라인, 나머지는 외부 저장

DYNAMIC:
- VARCHAR(255): 최대 255바이트 인라인 저장
- TEXT, BLOB: 40바이트만 인라인, 나머지는 외부 저장
- 페이지 공간 효율 향상
```

---

## 실무 활용 정리

### MySQL의 저장소 최적화

**1. 페이지 크기 선택**:
```sql
-- 워크로드별 권장 페이지 크기
-- OLTP (트랜잭션 처리): 8-16KB (기본값)
-- OLAP (분석 워크로드): 32-64KB

-- 테이블 생성 시 지정 (MySQL 5.7+)
CREATE TABLE analytics_data (...)
ROW_FORMAT=COMPRESSED KEY_BLOCK_SIZE=8;
```

**2. 버퍼 풀 튜닝**:
```sql
-- 버퍼 풀 크기 동적 조정 (MySQL 5.7+)
SET GLOBAL innodb_buffer_pool_size = 12G;  -- 재시작 불필요

-- 버퍼 풀 워밍업 (재시작 시 캐시 복원)
innodb_buffer_pool_dump_at_shutdown = ON
innodb_buffer_pool_load_at_startup = ON

-- 버퍼 풀 상태 확인
SHOW ENGINE INNODB STATUS\G
-- Buffer pool hit rate 확인 (99% 이상 권장)
```

**3. I/O 성능 모니터링**:
```sql
-- 느린 쿼리의 I/O 확인
SELECT
    digest_text AS query,
    sum_rows_examined / count_star AS avg_rows_examined,
    sum_rows_sent / count_star AS avg_rows_returned,
    sum_created_tmp_tables / count_star AS avg_tmp_tables
FROM performance_schema.events_statements_summary_by_digest
WHERE sum_timer_wait > 1000000000000  -- 1초 이상
ORDER BY sum_timer_wait DESC
LIMIT 10;
```

**4. 테이블스페이스 관리**:
```sql
-- 테이블별 파일 확인
SHOW VARIABLES LIKE 'innodb_file_per_table';  -- ON 권장

-- 테이블스페이스 크기 확인
SELECT
    table_schema,
    table_name,
    ROUND(data_length / 1024 / 1024, 2) AS data_mb,
    ROUND(index_length / 1024 / 1024, 2) AS index_mb,
    ROUND((data_length + index_length) / 1024 / 1024, 2) AS total_mb
FROM information_schema.TABLES
WHERE table_schema NOT IN ('information_schema', 'mysql', 'performance_schema')
ORDER BY (data_length + index_length) DESC
LIMIT 20;
```

### 성능 최적화 체크리스트

**1. 버퍼 풀**:
```sql
-- ✅ 버퍼 풀 크기: 전체 RAM의 50-75%
-- ✅ 히트율: 99% 이상
-- ✅ 인스턴스: 1GB 이상일 때 8개 권장
```

**2. I/O 설정**:
```sql
-- ✅ innodb_flush_log_at_trx_commit = 1 (내구성)
-- ✅ innodb_flush_method = O_DIRECT (Linux)
-- ✅ innodb_io_capacity = 200 (HDD) / 2000 (SSD)
```

**3. 페이지 압축**:
```sql
-- ✅ 읽기가 많은 테이블: ROW_FORMAT=COMPRESSED
-- ✅ OLAP 워크로드: KEY_BLOCK_SIZE=8
```

---

## 참고 자료

- [MySQL InnoDB Storage Engine](https://dev.mysql.com/doc/refman/8.0/en/innodb-storage-engine.html)
- [InnoDB Page Structure](https://dev.mysql.com/doc/internals/en/innodb-page-structure.html)
- [PostgreSQL Page Layout](https://www.postgresql.org/docs/current/storage-page-layout.html)
- [Database Internals (Book)](https://www.databass.dev/)
- [Java NIO ByteBuffer](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html)
