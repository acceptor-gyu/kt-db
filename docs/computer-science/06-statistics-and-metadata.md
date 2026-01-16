# 통계 & 메타데이터 관리

## 개요

데이터베이스의 **메타데이터(Metadata)**는 "데이터에 대한 데이터"로, 테이블 구조, 인덱스 정보, 제약조건 등을 포함합니다. **통계(Statistics)**는 데이터의 분포와 특성을 나타내며, 쿼리 옵티마이저가 최적의 실행 계획을 선택하는 데 필수적입니다.

---

## 1. 메타데이터의 종류

### 시스템 카탈로그 (System Catalog)

데이터베이스의 모든 객체에 대한 정보를 저장하는 특수 테이블입니다.

```
┌─────────────────────────────────┐
│     System Catalog              │
├─────────────────────────────────┤
│  • Tables                       │
│  • Columns                      │
│  • Indexes                      │
│  • Constraints                  │
│  • Views                        │
│  • Stored Procedures            │
│  • Users & Permissions          │
│  • Statistics                   │
└─────────────────────────────────┘
```

### MySQL의 System Catalog

```sql
-- information_schema 데이터베이스
SELECT * FROM information_schema.TABLES;
SELECT * FROM information_schema.COLUMNS;
SELECT * FROM information_schema.STATISTICS;
```

### PostgreSQL의 System Catalog

```sql
-- pg_catalog 스키마
SELECT * FROM pg_tables;
SELECT * FROM pg_indexes;
SELECT * FROM pg_stats;
```

---

## 2. 테이블 메타데이터

### 구현 코드

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/document/TableMetadata.kt`

```kotlin
@Document(indexName = "table_metadata")
data class TableMetadata(
    @Id
    val id: String? = null,

    val name: String,                   // 테이블명

    val columns: List<ColumnDefinition>, // 컬럼 정의 목록

    val primaryKey: String? = null,      // 기본 키 컬럼명

    val status: TableStatus = TableStatus.ACTIVE,  // 테이블 상태

    @Field(type = FieldType.Date)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Field(type = FieldType.Date)
    val updatedAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 컬럼 정의
 */
data class ColumnDefinition(
    val name: String,           // 컬럼명
    val dataType: DataType,     // 데이터 타입
    val nullable: Boolean = true,  // NULL 허용 여부
    val defaultValue: Any? = null  // 기본값
)

enum class DataType {
    INT,
    BIGINT,
    VARCHAR,
    TEXT,
    DATE,
    TIMESTAMP,
    BOOLEAN,
    DECIMAL
}

enum class TableStatus {
    ACTIVE,     // 사용 중
    DROPPED,    // 삭제됨 (소프트 삭제)
    ARCHIVED    // 아카이브됨
}
```

### 사용 예시

```kotlin
val tableMetadata = TableMetadata(
    name = "users",
    columns = listOf(
        ColumnDefinition(name = "id", dataType = DataType.INT, nullable = false),
        ColumnDefinition(name = "name", dataType = DataType.VARCHAR, nullable = false),
        ColumnDefinition(name = "age", dataType = DataType.INT, nullable = true),
        ColumnDefinition(name = "email", dataType = DataType.VARCHAR, nullable = true)
    ),
    primaryKey = "id",
    status = TableStatus.ACTIVE
)

// Elasticsearch에 저장
tableMetadataRepository.save(tableMetadata)
```

### 메타데이터 조회

```kotlin
@Service
class MetadataService(
    private val tableMetadataRepository: TableMetadataRepository
) {

    /**
     * 테이블 메타데이터 조회
     */
    fun getTableMetadata(tableName: String): TableMetadata? {
        return tableMetadataRepository.findByName(tableName)
    }

    /**
     * 컬럼 존재 여부 확인
     */
    fun hasColumn(tableName: String, columnName: String): Boolean {
        val metadata = getTableMetadata(tableName) ?: return false
        return metadata.columns.any { it.name == columnName }
    }

    /**
     * 컬럼 타입 조회
     */
    fun getColumnType(tableName: String, columnName: String): DataType? {
        val metadata = getTableMetadata(tableName) ?: return null
        return metadata.columns.find { it.name == columnName }?.dataType
    }
}
```

---

## 3. 인덱스 메타데이터

### 구현 코드

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/document/IndexMetadata.kt`

```kotlin
@Document(indexName = "index_metadata")
data class IndexMetadata(
    @Id
    val id: String? = null,

    val name: String,              // 인덱스명

    val tableName: String,         // 대상 테이블명

    val columns: List<String>,     // 인덱스 컬럼 목록 (순서 중요!)

    val indexType: IndexType = IndexType.BTREE,  // 인덱스 타입

    val isUnique: Boolean = false, // 유니크 인덱스 여부

    val status: IndexStatus = IndexStatus.ACTIVE,

    @Field(type = FieldType.Date)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class IndexType {
    BTREE,      // B-tree 인덱스 (기본, 범위 검색 좋음)
    HASH,       // 해시 인덱스 (등호 검색만 가능)
    FULLTEXT    // 전문 검색 인덱스
}

enum class IndexStatus {
    ACTIVE,     // 사용 중
    BUILDING,   // 생성 중
    DISABLED,   // 비활성화
    DROPPED     // 삭제됨
}
```

### 복합 인덱스 (Composite Index)

```kotlin
// 단일 컬럼 인덱스
val singleIndex = IndexMetadata(
    name = "idx_age",
    tableName = "users",
    columns = listOf("age"),
    indexType = IndexType.BTREE
)

// 복합 인덱스 (순서 중요!)
val compositeIndex = IndexMetadata(
    name = "idx_name_age",
    tableName = "users",
    columns = listOf("name", "age"),  // name이 첫 번째, age가 두 번째
    indexType = IndexType.BTREE
)

// 사용 가능한 쿼리:
// ✅ WHERE name = 'John'               (첫 번째 컬럼만)
// ✅ WHERE name = 'John' AND age > 20  (두 컬럼 모두)
// ❌ WHERE age > 20                    (첫 번째 컬럼 없음)
```

### 유니크 인덱스

```kotlin
val uniqueIndex = IndexMetadata(
    name = "idx_email_unique",
    tableName = "users",
    columns = listOf("email"),
    isUnique = true  // 중복 값 허용 안 함
)

// 삽입 시 자동 검증:
// INSERT INTO users (email) VALUES ('test@example.com')  ✅
// INSERT INTO users (email) VALUES ('test@example.com')  ❌ (중복!)
```

---

## 4. 테이블 통계

### 핵심 개념

통계는 쿼리 옵티마이저가 **행 수를 추정**하고 **비용을 계산**하는 데 사용됩니다.

### 구현 코드

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/document/TableStatistics.kt`

```kotlin
@Document(indexName = "table_statistics")
data class TableStatistics(
    @Id
    val id: String? = null,

    val tableName: String,         // 테이블명

    val rowCount: Long,            // 총 행 개수

    val columnStatistics: Map<String, ColumnStatistics>,  // 컬럼별 통계

    @Field(type = FieldType.Date)
    val lastUpdated: LocalDateTime = LocalDateTime.now()
)

/**
 * 컬럼별 통계 정보
 */
data class ColumnStatistics(
    val columnName: String,

    val distinctCount: Long,       // 고유 값의 개수 (Cardinality)

    val nullCount: Long,           // NULL 값의 개수

    val minValue: Any? = null,     // 최솟값

    val maxValue: Any? = null,     // 최댓값

    val avgLength: Double? = null  // 평균 길이 (VARCHAR 등)
) {
    /**
     * 선택도 (Selectivity) 계산
     */
    fun selectivity(): Double {
        if (distinctCount == 0L) return 0.0
        return 1.0 / distinctCount
    }
}
```

### 통계 수집

```kotlin
@Service
class StatisticsCollector(
    private val tableService: TableService,
    private val statisticsRepository: TableStatisticsRepository
) {

    /**
     * 테이블 통계 수집
     */
    fun collectStatistics(tableName: String) {
        val table = tableService.getTable(tableName)
            ?: throw TableNotFoundException("Table '$tableName' not found")

        val rowCount = table.rowCount()
        val columnStats = table.columns.associate { columnName ->
            columnName to collectColumnStatistics(table, columnName)
        }

        val statistics = TableStatistics(
            tableName = tableName,
            rowCount = rowCount,
            columnStatistics = columnStats
        )

        statisticsRepository.save(statistics)
    }

    /**
     * 컬럼 통계 수집
     */
    private fun collectColumnStatistics(
        table: Table,
        columnName: String
    ): ColumnStatistics {
        val values = table.getColumnValues(columnName)

        // 고유 값 개수
        val distinctCount = values.toSet().size.toLong()

        // NULL 개수
        val nullCount = values.count { it == null }.toLong()

        // 최솟값, 최댓값
        val nonNullValues = values.filterNotNull()
        val minValue = nonNullValues.minOrNull()
        val maxValue = nonNullValues.maxOrNull()

        // 평균 길이 (문자열인 경우)
        val avgLength = if (nonNullValues.firstOrNull() is String) {
            nonNullValues.map { (it as String).length }.average()
        } else null

        return ColumnStatistics(
            columnName = columnName,
            distinctCount = distinctCount,
            nullCount = nullCount,
            minValue = minValue,
            maxValue = maxValue,
            avgLength = avgLength
        )
    }

    /**
     * 증분 통계 업데이트 (INSERT 시)
     */
    fun updateStatisticsOnInsert(tableName: String, row: Map<String, Any>) {
        val stats = statisticsRepository.findByTableName(tableName) ?: return

        // 행 개수 증가
        val updatedStats = stats.copy(
            rowCount = stats.rowCount + 1,
            columnStatistics = stats.columnStatistics.mapValues { (columnName, colStats) ->
                val newValue = row[columnName]

                if (newValue == null) {
                    // NULL 값 추가
                    colStats.copy(nullCount = colStats.nullCount + 1)
                } else {
                    // 고유 값 개수 업데이트 (정확하지 않음, 주기적 재수집 필요)
                    colStats.copy(
                        distinctCount = colStats.distinctCount + 1,  // 근사치
                        minValue = minOf(colStats.minValue as Comparable<Any>, newValue as Comparable<Any>),
                        maxValue = maxOf(colStats.maxValue as Comparable<Any>, newValue as Comparable<Any>)
                    )
                }
            }
        )

        statisticsRepository.save(updatedStats)
    }
}
```

### 통계 사용 예시

```kotlin
// 1. 통계 수집
statisticsCollector.collectStatistics("users")

// 2. 통계 조회
val stats = statisticsRepository.findByTableName("users")

println("Total rows: ${stats.rowCount}")  // 1,000,000

val ageStats = stats.columnStatistics["age"]
println("Distinct ages: ${ageStats.distinctCount}")  // 100
println("Selectivity: ${ageStats.selectivity()}")    // 0.01 (1%)

// 3. 행 수 추정
// WHERE age = 25
val estimatedRows = stats.rowCount * ageStats.selectivity()
println("Estimated rows: $estimatedRows")  // 10,000
```

---

## 5. 히스토그램 (Histogram)

### 핵심 개념

**히스토그램**은 데이터의 분포를 더 정확하게 표현합니다. 단순 통계(min, max, distinct)보다 정밀한 행 수 추정이 가능합니다.

### 등폭 히스토그램 (Equi-Width Histogram)

```kotlin
data class Histogram(
    val columnName: String,
    val buckets: List<Bucket>
)

data class Bucket(
    val lowerBound: Any,     // 하한
    val upperBound: Any,     // 상한
    val frequency: Long      // 이 범위에 속하는 행 수
)

// 예시: age 컬럼 (0-100)
val histogram = Histogram(
    columnName = "age",
    buckets = listOf(
        Bucket(lowerBound = 0, upperBound = 20, frequency = 100),
        Bucket(lowerBound = 20, upperBound = 40, frequency = 500),
        Bucket(lowerBound = 40, upperBound = 60, frequency = 300),
        Bucket(lowerBound = 60, upperBound = 80, frequency = 80),
        Bucket(lowerBound = 80, upperBound = 100, frequency = 20)
    )
)

// WHERE age > 50 추정
// 50은 [40, 60) 버킷에 속함
// 추정: (60-50)/(60-40) * 300 + 80 + 20 = 250
```

### 등깊이 히스토그램 (Equi-Depth Histogram)

각 버킷이 비슷한 개수의 행을 포함하도록 분할합니다.

```kotlin
// 총 1000행을 5개 버킷으로 분할 (각 200행)
val equiDepthHistogram = Histogram(
    columnName = "salary",
    buckets = listOf(
        Bucket(lowerBound = 20000, upperBound = 35000, frequency = 200),
        Bucket(lowerBound = 35000, upperBound = 45000, frequency = 200),
        Bucket(lowerBound = 45000, upperBound = 60000, frequency = 200),
        Bucket(lowerBound = 60000, upperBound = 80000, frequency = 200),
        Bucket(lowerBound = 80000, upperBound = 200000, frequency = 200)
    )
)
```

**장점**: 데이터가 한쪽으로 치우쳐도 추정 정확도 유지

---

## 6. 통계 갱신 전략

### 1) 주기적 갱신

```sql
-- MySQL
ANALYZE TABLE users;

-- PostgreSQL
ANALYZE users;
```

**언제 실행하는가?**
- 대량의 데이터 삽입/삭제 후
- 쿼리 성능이 갑자기 나빠졌을 때
- 매일 새벽 배치 작업으로 (자동화)

### 2) 증분 갱신

```kotlin
// INSERT/UPDATE/DELETE 시마다 통계 부분 업데이트
override fun insert(tableName: String, row: Map<String, Any>) {
    // 데이터 삽입
    tableService.insert(tableName, row)

    // 통계 증분 업데이트
    statisticsCollector.updateStatisticsOnInsert(tableName, row)
}
```

**장점**: 통계가 항상 최신 상태
**단점**: 삽입 성능 저하

### 3) 하이브리드 접근

```kotlin
// 1000번 삽입마다 통계 재수집
var insertCount = 0

override fun insert(tableName: String, row: Map<String, Any>) {
    tableService.insert(tableName, row)

    if (++insertCount % 1000 == 0) {
        statisticsCollector.collectStatistics(tableName)
    }
}
```

---

## 7. 실무 활용 사례

### MySQL의 통계

```sql
-- 테이블 통계 조회
SELECT * FROM mysql.innodb_table_stats WHERE table_name = 'users';

-- 인덱스 통계 조회
SELECT * FROM mysql.innodb_index_stats WHERE table_name = 'users';

-- 통계 수동 갱신
ANALYZE TABLE users;

-- 히스토그램 생성 (MySQL 8.0+)
ANALYZE TABLE users UPDATE HISTOGRAM ON age, salary;

-- 히스토그램 조회
SELECT * FROM information_schema.COLUMN_STATISTICS;
```

### PostgreSQL의 통계

```sql
-- 통계 조회
SELECT * FROM pg_stats WHERE tablename = 'users';

-- 특정 컬럼의 통계
SELECT n_distinct, null_frac, avg_width
FROM pg_stats
WHERE tablename = 'users' AND attname = 'age';

-- 히스토그램 조회
SELECT histogram_bounds FROM pg_stats
WHERE tablename = 'users' AND attname = 'age';
```

---

## 8. 메타데이터 캐싱

### 핵심 개념

메타데이터는 자주 조회되지만 거의 변경되지 않으므로, 캐싱이 효과적입니다.

### 구현 예시

```kotlin
@Service
class CachedMetadataService(
    private val tableMetadataRepository: TableMetadataRepository
) {
    // Caffeine 캐시 사용
    private val cache: LoadingCache<String, TableMetadata> = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build { tableName ->
            tableMetadataRepository.findByName(tableName)
                ?: throw TableNotFoundException("Table '$tableName' not found")
        }

    fun getTableMetadata(tableName: String): TableMetadata {
        return cache.get(tableName)
    }

    fun invalidate(tableName: String) {
        cache.invalidate(tableName)
    }
}
```

---

## 9. 학습 포인트 요약

| 개념 | 역할 | 중요성 |
|-----|------|--------|
| **TableMetadata** | 테이블 구조 정의 | 스키마 검증 |
| **IndexMetadata** | 인덱스 정보 | 쿼리 최적화 |
| **TableStatistics** | 데이터 분포 통계 | 비용 추정 |
| **Histogram** | 정밀한 분포 표현 | 정확한 행 수 추정 |
| **Selectivity** | 조건 만족 비율 | 인덱스 선택 |

---

## 참고 자료

- [MySQL ANALYZE TABLE](https://dev.mysql.com/doc/refman/8.0/en/analyze-table.html)
- [PostgreSQL Statistics](https://www.postgresql.org/docs/current/planner-stats.html)
- [MySQL Histograms](https://dev.mysql.com/doc/refman/8.0/en/optimizer-statistics.html)
- [Database System Concepts - Query Optimization](https://www.db-book.com/)
