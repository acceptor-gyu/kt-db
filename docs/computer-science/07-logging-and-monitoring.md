# 로깅 & 모니터링

## 개요

운영 환경에서 데이터베이스의 동작을 추적하고 문제를 진단하려면 체계적인 **로깅(Logging)**과 **모니터링(Monitoring)**이 필수입니다. 이 문서에서는 쿼리 로그, 성능 메트릭 수집, 그리고 Elasticsearch/Kibana를 활용한 로그 분석에 대해 설명합니다.

---

## 1. 로깅의 중요성

### 왜 로그가 필요한가?

1. **성능 분석**
   - 느린 쿼리 식별 (Slow Query Log)
   - 병목 지점 파악

2. **디버깅**
   - 에러 발생 시 원인 추적
   - 재현 불가능한 버그 분석

3. **감사 (Audit)**
   - 누가, 언제, 무엇을 실행했는지 추적
   - 보안 규정 준수

4. **용량 계획**
   - 쿼리 패턴 분석
   - 리소스 사용량 예측

---

## 2. 쿼리 로그 아키텍처

### 전체 구조

```
SQL 쿼리 실행
    ↓
┌──────────────────────┐
│  Query Executor      │
└──────────────────────┘
    ↓
┌──────────────────────┐
│  Query Logger        │  ← 실행 정보 수집
└──────────────────────┘
    ↓
┌──────────────────────┐
│  Elasticsearch       │  ← 로그 저장
└──────────────────────┘
    ↓
┌──────────────────────┐
│  Kibana Dashboard    │  ← 시각화 & 분석
└──────────────────────┘
```

### 로그 데이터 모델

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/document/QueryLog.kt`

```kotlin
@Document(indexName = "query_logs")
data class QueryLog(
    @Id
    val id: String? = null,

    val queryType: QueryType,      // SELECT, INSERT, UPDATE, DELETE, CREATE

    val sqlText: String,           // 실제 SQL 문

    @Field(type = FieldType.Date)
    val timestamp: LocalDateTime = LocalDateTime.now(),

    val executionTimeMs: Long,     // 실행 시간 (밀리초)

    val status: QueryStatus,       // SUCCESS, ERROR, TIMEOUT

    val errorMessage: String? = null,  // 에러 발생 시 메시지

    val affectedTables: List<String>,  // 영향받은 테이블 목록

    val affectedRows: Long = 0,    // 영향받은 행 수

    val connectionId: Long,        // 연결 ID

    val username: String? = null   // 실행 사용자
)

enum class QueryType {
    SELECT,   // DQL (Data Query Language)
    INSERT,   // DML (Data Manipulation Language)
    UPDATE,
    DELETE,
    CREATE,   // DDL (Data Definition Language)
    ALTER,
    DROP,
    DCL,      // Data Control Language (GRANT, REVOKE)
    TCL       // Transaction Control Language (COMMIT, ROLLBACK)
}

enum class QueryStatus {
    SUCCESS,
    ERROR,
    TIMEOUT,
    CANCELLED
}
```

### 쿼리 로거 구현

```kotlin
@Service
class QueryLogger(
    private val queryLogRepository: QueryLogRepository
) {

    /**
     * 쿼리 실행 전후로 로그 기록
     */
    fun <T> logQuery(
        sql: String,
        connectionId: Long,
        username: String?,
        queryType: QueryType,
        affectedTables: List<String>,
        block: () -> T
    ): T {
        val startTime = System.currentTimeMillis()
        var status = QueryStatus.SUCCESS
        var errorMessage: String? = null
        var affectedRows = 0L

        return try {
            val result = block()

            // 결과에서 영향받은 행 수 추출
            affectedRows = when (result) {
                is Long -> result
                is List<*> -> result.size.toLong()
                else -> 0
            }

            result
        } catch (e: Exception) {
            status = QueryStatus.ERROR
            errorMessage = e.message
            throw e
        } finally {
            val executionTime = System.currentTimeMillis() - startTime

            // 로그 저장 (비동기)
            saveLogAsync(
                queryLog = QueryLog(
                    queryType = queryType,
                    sqlText = sql,
                    executionTimeMs = executionTime,
                    status = status,
                    errorMessage = errorMessage,
                    affectedTables = affectedTables,
                    affectedRows = affectedRows,
                    connectionId = connectionId,
                    username = username
                )
            )
        }
    }

    /**
     * 비동기 로그 저장
     */
    @Async
    private fun saveLogAsync(queryLog: QueryLog) {
        try {
            queryLogRepository.save(queryLog)
        } catch (e: Exception) {
            // 로그 저장 실패는 원본 쿼리 실행에 영향 없음
            logger.error("Failed to save query log", e)
        }
    }
}
```

### 사용 예시

```kotlin
@Service
class TableService(
    private val queryLogger: QueryLogger
) {

    fun executeQuery(sql: String, connectionId: Long): List<Row> {
        return queryLogger.logQuery(
            sql = sql,
            connectionId = connectionId,
            username = getCurrentUsername(),
            queryType = QueryType.SELECT,
            affectedTables = listOf("users")
        ) {
            // 실제 쿼리 실행
            executeSelectQuery(sql)
        }
    }
}
```

---

## 3. Slow Query Log (슬로우 쿼리 로그)

### 핵심 개념

**Slow Query Log**는 실행 시간이 임계값을 초과한 쿼리만 기록하여, 성능 문제를 빠르게 파악할 수 있게 합니다.

### 구현

```kotlin
@Service
class SlowQueryDetector(
    private val slowQueryRepository: SlowQueryRepository,
    @Value("\${db.slow-query-threshold-ms:1000}")
    private val thresholdMs: Long = 1000  // 기본값: 1초
) {

    fun checkAndLog(queryLog: QueryLog) {
        if (queryLog.executionTimeMs >= thresholdMs) {
            val slowQuery = SlowQueryLog(
                originalLog = queryLog,
                threshold = thresholdMs,
                severity = calculateSeverity(queryLog.executionTimeMs)
            )

            slowQueryRepository.save(slowQuery)

            // 알림 발송 (옵션)
            if (slowQuery.severity == Severity.CRITICAL) {
                sendAlert(slowQuery)
            }
        }
    }

    private fun calculateSeverity(executionTimeMs: Long): Severity {
        return when {
            executionTimeMs < thresholdMs -> Severity.NORMAL
            executionTimeMs < thresholdMs * 2 -> Severity.WARNING
            executionTimeMs < thresholdMs * 5 -> Severity.HIGH
            else -> Severity.CRITICAL
        }
    }
}

enum class Severity {
    NORMAL,
    WARNING,
    HIGH,
    CRITICAL
}
```

### MySQL의 Slow Query Log

```sql
-- Slow Query Log 활성화
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 2;  -- 2초 이상 쿼리 기록

-- 로그 파일 위치
SET GLOBAL slow_query_log_file = '/var/log/mysql/slow-query.log';

-- 로그 조회
SELECT * FROM mysql.slow_log;
```

---

## 4. Elasticsearch 통합

### 핵심 개념

Elasticsearch는 **대량의 로그를 빠르게 검색하고 집계**할 수 있는 검색 엔진입니다.

### Repository 구현

**파일 위치**: `db-server/src/main/kotlin/study/db/server/elasticsearch/repository/QueryLogRepository.kt`

```kotlin
interface QueryLogRepository : ElasticsearchRepository<QueryLog, String> {

    /**
     * 특정 테이블에 대한 쿼리 로그 검색
     */
    fun findByAffectedTablesContaining(tableName: String): List<QueryLog>

    /**
     * 시간 범위로 검색
     */
    fun findByTimestampBetween(start: LocalDateTime, end: LocalDateTime): List<QueryLog>

    /**
     * 에러 쿼리만 검색
     */
    fun findByStatus(status: QueryStatus): List<QueryLog>

    /**
     * 느린 쿼리 검색 (실행 시간 기준)
     */
    fun findByExecutionTimeMsGreaterThan(thresholdMs: Long): List<QueryLog>
}
```

### 복잡한 쿼리 (Native Query)

```kotlin
@Repository
class QueryLogCustomRepository(
    private val elasticsearchOperations: ElasticsearchOperations
) {

    /**
     * 쿼리 타입별 평균 실행 시간
     */
    fun getAverageExecutionTimeByQueryType(): Map<QueryType, Double> {
        val query = NativeQuery.builder()
            .withAggregation("avg_by_type",
                Aggregation.of { a ->
                    a.terms { t -> t.field("queryType") }
                        .aggregations("avg_time",
                            Aggregation.of { sub ->
                                sub.avg { avg -> avg.field("executionTimeMs") }
                            }
                        )
                }
            )
            .build()

        val result = elasticsearchOperations.search(query, QueryLog::class.java)

        // 집계 결과 파싱
        return parseAggregationResult(result)
    }

    /**
     * 시간대별 쿼리 개수
     */
    fun getQueryCountByHour(start: LocalDateTime, end: LocalDateTime): List<HourlyCount> {
        val query = NativeQuery.builder()
            .withQuery { q ->
                q.range { r ->
                    r.field("timestamp")
                        .gte(JsonData.of(start))
                        .lte(JsonData.of(end))
                }
            }
            .withAggregation("hourly_count",
                Aggregation.of { a ->
                    a.dateHistogram { dh ->
                        dh.field("timestamp")
                            .calendarInterval(CalendarInterval.Hour)
                    }
                }
            )
            .build()

        val result = elasticsearchOperations.search(query, QueryLog::class.java)

        return parseHourlyCount(result)
    }
}

data class HourlyCount(
    val hour: LocalDateTime,
    val count: Long
)
```

---

## 5. Kibana 대시보드

### 대시보드 구성

1. **쿼리 성능 모니터링**
   - 평균 실행 시간 (시계열)
   - 쿼리 타입별 분포 (파이 차트)
   - Slow Query Top 10 (테이블)

2. **에러 추적**
   - 에러율 (시계열)
   - 에러 타입별 분포
   - 최근 에러 로그 (테이블)

3. **테이블 사용량**
   - 테이블별 쿼리 개수 (막대 그래프)
   - 가장 많이 사용되는 테이블 Top 10

4. **사용자 활동**
   - 사용자별 쿼리 개수
   - 연결 개수 (시계열)

### Kibana Query Language (KQL) 예시

```
# 1. 느린 SELECT 쿼리
queryType: SELECT AND executionTimeMs > 1000

# 2. 특정 테이블 관련 에러
affectedTables: "users" AND status: ERROR

# 3. 지난 1시간 동안의 INSERT 쿼리
queryType: INSERT AND timestamp > now-1h

# 4. 특정 사용자의 쿼리
username: "john.doe"

# 5. 100행 이상 영향받은 쿼리
affectedRows > 100
```

---

## 6. 실시간 모니터링

### 메트릭 수집

```kotlin
@Service
class MetricsCollector {
    private val meterRegistry: MeterRegistry

    fun recordQueryExecution(queryType: QueryType, executionTimeMs: Long) {
        // 실행 시간 히스토그램
        Timer.builder("db.query.execution.time")
            .tag("type", queryType.name)
            .register(meterRegistry)
            .record(executionTimeMs, TimeUnit.MILLISECONDS)

        // 쿼리 개수 카운터
        Counter.builder("db.query.count")
            .tag("type", queryType.name)
            .register(meterRegistry)
            .increment()
    }

    fun recordConnection(active: Boolean) {
        // 활성 연결 수
        Gauge.builder("db.connections.active") { getActiveConnectionCount() }
            .register(meterRegistry)
    }

    fun recordError(queryType: QueryType) {
        // 에러 카운터
        Counter.builder("db.query.errors")
            .tag("type", queryType.name)
            .register(meterRegistry)
            .increment()
    }
}
```

### Prometheus 엔드포인트

```kotlin
@Configuration
class MetricsConfig {
    @Bean
    fun prometheusRegistry(): PrometheusMeterRegistry {
        return PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    }
}

@RestController
class MetricsController(
    private val prometheusRegistry: PrometheusMeterRegistry
) {
    @GetMapping("/actuator/prometheus")
    fun prometheus(): String {
        return prometheusRegistry.scrape()
    }
}
```

### Grafana 대시보드 예시

```yaml
# Prometheus Query 예시

# 1. 초당 쿼리 개수 (QPS)
rate(db_query_count_total[1m])

# 2. 평균 실행 시간 (P50, P95, P99)
histogram_quantile(0.50, db_query_execution_time_bucket)
histogram_quantile(0.95, db_query_execution_time_bucket)
histogram_quantile(0.99, db_query_execution_time_bucket)

# 3. 에러율
rate(db_query_errors_total[1m]) / rate(db_query_count_total[1m])

# 4. 활성 연결 수
db_connections_active
```

---

## 7. 로그 보존 정책

### 인덱스 라이프사이클 관리 (ILM)

```json
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "50GB",
            "max_age": "7d"
          }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink": {
            "number_of_shards": 1
          },
          "forcemerge": {
            "max_num_segments": 1
          }
        }
      },
      "cold": {
        "min_age": "30d",
        "actions": {
          "freeze": {}
        }
      },
      "delete": {
        "min_age": "90d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

**설명**:
- **Hot (0-7일)**: 빠른 검색을 위해 SSD에 저장, 자주 조회됨
- **Warm (7-30일)**: 압축하여 저장, 가끔 조회됨
- **Cold (30-90일)**: 거의 조회 안 됨, 아카이브
- **Delete (90일+)**: 삭제

---

## 8. 실무 활용 사례

### MySQL의 로깅

```sql
-- 1. General Query Log (모든 쿼리)
SET GLOBAL general_log = 'ON';
SET GLOBAL general_log_file = '/var/log/mysql/general.log';

-- 2. Slow Query Log
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 2;

-- 3. Binary Log (복제 & 복구)
SET GLOBAL binlog_format = 'ROW';

-- 4. Error Log
-- my.cnf에서 설정
log_error = /var/log/mysql/error.log
```

### PostgreSQL의 로깅

```sql
-- postgresql.conf 설정

-- 1. 모든 쿼리 로깅
log_statement = 'all'

-- 2. 느린 쿼리만 로깅
log_min_duration_statement = 1000  -- 1초 이상

-- 3. 쿼리 실행 계획 로깅
auto_explain.log_min_duration = 1000

-- 4. 연결 로깅
log_connections = on
log_disconnections = on
```

---

## 9. 성능 고려사항

### 로깅 오버헤드 최소화

1. **비동기 로깅**
   ```kotlin
   @Async
   fun saveLog(log: QueryLog) {
       repository.save(log)
   }
   ```

2. **배치 삽입**
   ```kotlin
   // 100개씩 모아서 한 번에 삽입
   repository.saveAll(logBuffer)
   ```

3. **샘플링**
   ```kotlin
   // 10%만 로깅
   if (Random.nextDouble() < 0.1) {
       saveLog(log)
   }
   ```

4. **필드 선택적 저장**
   ```kotlin
   // SQL 텍스트가 너무 길면 자름
   val truncatedSql = if (sql.length > 1000) {
       sql.substring(0, 1000) + "..."
   } else sql
   ```

---

## 10. 학습 포인트 요약

| 개념 | 역할 | 도구 |
|-----|------|------|
| **Query Log** | 모든 쿼리 기록 | Elasticsearch |
| **Slow Query Log** | 느린 쿼리만 기록 | 임계값 설정 |
| **Metrics** | 실시간 성능 지표 | Prometheus + Grafana |
| **Dashboard** | 로그 시각화 | Kibana |
| **ILM** | 로그 수명 관리 | Elasticsearch ILM |

---

## 참고 자료

- [Elasticsearch Guide](https://www.elastic.co/guide/en/elasticsearch/reference/current/index.html)
- [Kibana User Guide](https://www.elastic.co/guide/en/kibana/current/index.html)
- [MySQL Slow Query Log](https://dev.mysql.com/doc/refman/8.0/en/slow-query-log.html)
- [PostgreSQL Logging](https://www.postgresql.org/docs/current/runtime-config-logging.html)
- [Prometheus Best Practices](https://prometheus.io/docs/practices/)
