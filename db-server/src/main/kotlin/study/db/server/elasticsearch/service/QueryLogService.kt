package study.db.server.elasticsearch.service

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import study.db.server.elasticsearch.document.QueryLog
import study.db.server.elasticsearch.document.QueryStatus
import study.db.server.elasticsearch.document.QueryType
import study.db.server.elasticsearch.repository.QueryLogRepository

/**
 * TODO: QueryType이 DQL(Data Query Language)인 경우, tableName으로 조회 후, 분석
 */
@Service
class QueryLogService(
    private val queryLogRepository: QueryLogRepository
) {
    private val logger = LoggerFactory.getLogger(QueryLogService::class.java)

    fun indexQueryLog(queryLog: QueryLog): QueryLog {
        return try {
            val savedLog = queryLogRepository.save(queryLog)
            logger.info("Indexed query log: ${savedLog.queryId}")
            savedLog
        } catch (e: Exception) {
            logger.error("Failed to index query log: ${queryLog.queryId}", e)
            throw e
        }
    }

    fun searchQueryLogs(
        queryType: QueryType? = null,
        tableName: String? = null,
        limit: Int = 100
    ): List<QueryLog> {
        return try {
            when {
                queryType != null && tableName != null -> {
                    queryLogRepository.findByQueryTypeAndAffectedTablesContaining(queryType, tableName)
                }
                else -> {
                    queryLogRepository.findAll(
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "timestamp"))
                    ).content
                }
            }.take(limit)
        } catch (e: Exception) {
            logger.error("Failed to search query logs", e)
            emptyList()
        }
    }

    fun getQueryStatistics(tableName: String): QueryStatistics? {
        return try {
            val queryLogs = queryLogRepository.findByAffectedTablesContaining(tableName)

            if (queryLogs.isEmpty()) {
                return null
            }

            val queryTypeCounts = queryLogs.groupingBy { it.queryType }.eachCount()
            val averageExecutionTime = queryLogs
                .mapNotNull { it.executionTimeMs }
                .average()
                .takeIf { !it.isNaN() }

            QueryStatistics(
                tableName = tableName,
                totalQueries = queryLogs.size,
                queryTypeCounts = queryTypeCounts,
                averageExecutionTimeMs = averageExecutionTime,
                successRate = queryLogs.count { it.status == QueryStatus.SUCCESS }
                    .toDouble() / queryLogs.size * 100
            )
        } catch (e: Exception) {
            logger.error("Failed to get query statistics for table: $tableName", e)
            null
        }
    }
}

data class QueryStatistics(
    val tableName: String,
    val totalQueries: Int,
    val queryTypeCounts: Map<QueryType, Int>,
    val averageExecutionTimeMs: Double?,
    val successRate: Double
)
