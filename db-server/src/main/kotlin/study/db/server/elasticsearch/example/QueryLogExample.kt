package study.db.server.elasticsearch.example

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import study.db.server.elasticsearch.document.QueryLog
import study.db.server.elasticsearch.document.QueryStatus
import study.db.server.elasticsearch.document.QueryType
import study.db.server.elasticsearch.service.QueryLogService
import java.time.Instant
import java.util.*

/**
 * QueryLogService 사용 예제
 *
 * 이 예제는 DML, DDL 명령어 로그를 Elasticsearch에 저장하고 조회하는 방법을 보여줍니다.
 */
@SpringBootApplication(scanBasePackages = ["study.db.server.elasticsearch"])
class QueryLogExampleApp {

    private val logger = LoggerFactory.getLogger(QueryLogExampleApp::class.java)

    @Bean
    fun runExample(queryLogService: QueryLogService): CommandLineRunner {
        return CommandLineRunner {
            try {
                // 1. DDL 쿼리 로그 저장 예제
                saveDDLExample(queryLogService)

                // 2. DML 쿼리 로그 저장 예제
                saveDMLExample(queryLogService)

                // 3. 쿼리 로그 검색 예제
                searchExample(queryLogService)

                // 4. 테이블 통계 조회 예제
                statisticsExample(queryLogService)

            } catch (e: Exception) {
                logger.error("Error in QueryLogExample", e)
            }
        }
    }

    private fun saveDDLExample(service: QueryLogService) {
        logger.info("=== DDL Query Log Example ===")

        val queryLog = QueryLog(
            queryId = UUID.randomUUID().toString(),
            queryType = QueryType.DDL,
            queryText = "CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(100), email VARCHAR(100))",
            connectionId = "conn-${System.currentTimeMillis()}",
            user = "admin",
            timestamp = Instant.now(),
            executionTimeMs = 45L,
            status = QueryStatus.SUCCESS,
            affectedTables = listOf("users"),
            rowsAffected = 0,
            metadata = mapOf(
                "operation" to "CREATE_TABLE",
                "columns" to 3
            )
        )

        val savedLog = service.indexQueryLog(queryLog)
        logger.info("DDL query log saved: ${savedLog.queryId}")

        // 저장된 로그 조회
        val retrieved = service.getQueryLogById(queryLog.queryId)
        logger.info("Retrieved log: $retrieved")
    }

    private fun saveDMLExample(service: QueryLogService) {
        logger.info("=== DML Query Log Example ===")

        val insertLog = QueryLog(
            queryId = UUID.randomUUID().toString(),
            queryType = QueryType.DML,
            queryText = "INSERT INTO users (id, name, email) VALUES (1, 'John Doe', 'john@example.com')",
            connectionId = "conn-${System.currentTimeMillis()}",
            user = "user1",
            timestamp = Instant.now(),
            executionTimeMs = 12L,
            status = QueryStatus.SUCCESS,
            affectedTables = listOf("users"),
            rowsAffected = 1
        )

        service.indexQueryLog(insertLog)
        logger.info("DML query log saved: ${insertLog.queryId}")

        // 실패한 쿼리 로그 예제
        val failedLog = QueryLog(
            queryId = UUID.randomUUID().toString(),
            queryType = QueryType.DML,
            queryText = "UPDATE users SET name = 'Jane Doe' WHERE id = 999",
            connectionId = "conn-${System.currentTimeMillis()}",
            user = "user1",
            timestamp = Instant.now(),
            executionTimeMs = 8L,
            status = QueryStatus.FAILED,
            errorMessage = "No rows found with id=999",
            affectedTables = listOf("users"),
            rowsAffected = 0
        )

        service.indexQueryLog(failedLog)
        logger.info("Failed DML query log saved: ${failedLog.queryId}")
    }

    private fun searchExample(service: QueryLogService) {
        logger.info("=== Query Log Search Example ===")

        // Wait a bit for indexing
        Thread.sleep(1000)

        // users 테이블에 대한 모든 DML 쿼리 검색
        val dmlLogs = service.searchQueryLogs(
            queryType = QueryType.DML,
            tableName = "users",
            limit = 10
        )

        logger.info("Found ${dmlLogs.size} DML queries for 'users' table:")
        dmlLogs.forEach { log ->
            logger.info("  - ${log.queryText} (${log.status}, ${log.executionTimeMs}ms)")
        }

        // 모든 DDL 쿼리 검색
        val ddlLogs = service.searchQueryLogs(
            queryType = QueryType.DDL,
            limit = 10
        )

        logger.info("Found ${ddlLogs.size} DDL queries:")
        ddlLogs.forEach { log ->
            logger.info("  - ${log.queryText} (${log.status})")
        }
    }

    private fun statisticsExample(service: QueryLogService) {
        logger.info("=== Query Statistics Example ===")

        val stats = service.getQueryStatistics("users")

        if (stats != null) {
            logger.info("Statistics for 'users' table:")
            logger.info("  Total queries: ${stats.totalQueries}")
            logger.info("  Query types: ${stats.queryTypeCounts}")
            logger.info("  Average execution time: ${stats.averageExecutionTimeMs}ms")
            logger.info("  Success rate: ${"%.2f".format(stats.successRate)}%")
        } else {
            logger.info("No statistics available for 'users' table")
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(QueryLogExampleApp::class.java, *args)
        }
    }
}
