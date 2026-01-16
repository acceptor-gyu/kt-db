package study.db.server.elasticsearch.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import study.db.server.db_engine.SqlParser
import study.db.server.elasticsearch.document.ExecutionStep
import study.db.server.elasticsearch.document.QueryPlan
import study.db.server.elasticsearch.repository.QueryPlanRepository
import java.security.MessageDigest
import java.time.Instant
import java.util.*

@Service
class ExplainService(
    private val sqlParser: SqlParser,
    private val tableMetadataService: TableMetadataService,
    private val indexMetadataService: IndexMetadataService,
    private val tableStatisticsService: TableStatisticsService,
    private val queryPlanRepository: QueryPlanRepository
) {
    private val logger = LoggerFactory.getLogger(ExplainService::class.java)

    /**
     * EXPLAIN 실행
     *
     * SQL을 분석하여 실행 계획을 생성합니다.
     */
    fun explain(sql: String): QueryPlan {
        return try {
            logger.info("Executing EXPLAIN for: $sql")

            // 1. SQL 파싱 (SqlParser 활용)
            val parsedQuery = try {
                sqlParser.parseQuery(sql)
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to parse SQL: ${e.message}", e)
            }

            // 2. 테이블 존재 확인
            val tableName = parsedQuery.tableName
            if (!tableMetadataService.tableExists(tableName)) {
                throw IllegalStateException("Table doesn't exist: $tableName")
            }

            // 3. WHERE 절 분석 (첫 번째 조건만 사용 - 간단한 구현)
            val firstCondition = parsedQuery.whereConditions.firstOrNull()
            val whereColumn = firstCondition?.column
            val whereClause = if (firstCondition != null) {
                "${firstCondition.column} ${firstCondition.operator} ${firstCondition.value}"
            } else {
                ""
            }

            // 4. SELECT 절 컬럼 추출
            val selectColumns = if (parsedQuery.selectColumns.contains("*")) {
                tableMetadataService.getTableColumns(tableName)?.map { it.name } ?: emptyList()
            } else {
                parsedQuery.selectColumns
            }

            // 6. 실행 계획 생성
            val executionSteps = generateExecutionPlan(tableName, whereColumn, whereClause, selectColumns)

            // 7. 비용 및 예상 행 수 계산
            val estimatedCost = executionSteps.sumOf { it.estimatedCost }
            val estimatedRows = executionSteps.lastOrNull()?.estimatedRows ?: 0

            // 8. Covered Query 판단
            val isCoveredQuery = executionSteps.any { it.isCovered }

            // 9. QueryPlan 생성 및 저장
            val queryHash = generateQueryHash(sql)
            val queryPlan = QueryPlan(
                planId = UUID.randomUUID().toString(),
                queryText = sql,
                queryHash = queryHash,
                executionSteps = executionSteps,
                estimatedCost = estimatedCost,
                estimatedRows = estimatedRows,
                isCoveredQuery = isCoveredQuery,
                generatedAt = Instant.now()
            )

            val saved = queryPlanRepository.save(queryPlan)
            logger.info("Generated query plan: $queryHash, covered=$isCoveredQuery")
            saved

        } catch (e: Exception) {
            logger.error("Failed to explain query: $sql", e)
            throw e
        }
    }

    /**
     * 실행 계획 생성
     */
    private fun generateExecutionPlan(
        tableName: String,
        whereColumn: String?,
        whereClause: String,
        selectColumns: List<String>
    ): List<ExecutionStep> {
        val steps = mutableListOf<ExecutionStep>()

        // 테이블 통계 조회
        val stats = tableStatisticsService.getStatistics(tableName)
        val totalRows = stats?.totalRows ?: 0

        if (whereColumn != null) {
            // WHERE 절이 있는 경우
            val selectivity = tableStatisticsService.calculateSelectivity(tableName, whereColumn)
            val decision = indexMetadataService.shouldUseIndexScan(tableName, whereColumn, selectivity)

            if (decision.useIndex && decision.selectedIndex != null) {
                // INDEX_SCAN 또는 COVERED_INDEX_SCAN
                val isCovered = isCoveredQuery(decision.selectedIndex.indexColumns, selectColumns, whereColumn)
                val stepType = if (isCovered) "COVERED_INDEX_SCAN" else "INDEX_SCAN"

                steps.add(
                    ExecutionStep(
                        stepId = 1,
                        stepType = stepType,
                        tableName = tableName,
                        indexUsed = decision.selectedIndex.indexName,
                        filterCondition = whereClause,
                        columnsAccessed = selectColumns,
                        estimatedCost = calculateIndexScanCost(totalRows, selectivity),
                        estimatedRows = (totalRows * selectivity).toLong(),
                        isCovered = isCovered,
                        description = buildDescription(stepType, decision, selectivity)
                    )
                )
            } else {
                // TABLE_SCAN
                steps.add(
                    ExecutionStep(
                        stepId = 1,
                        stepType = "TABLE_SCAN",
                        tableName = tableName,
                        indexUsed = null,
                        filterCondition = whereClause,
                        columnsAccessed = selectColumns,
                        estimatedCost = totalRows.toDouble(),
                        estimatedRows = (totalRows * selectivity).toLong(),
                        isCovered = false,
                        description = "Full table scan on $tableName. ${decision.reason}"
                    )
                )
            }
        } else {
            // WHERE 절이 없는 경우 (Full Table Scan)
            steps.add(
                ExecutionStep(
                    stepId = 1,
                    stepType = "TABLE_SCAN",
                    tableName = tableName,
                    indexUsed = null,
                    filterCondition = null,
                    columnsAccessed = selectColumns,
                    estimatedCost = totalRows.toDouble(),
                    estimatedRows = totalRows,
                    isCovered = false,
                    description = "Full table scan on $tableName (no WHERE clause)"
                )
            )
        }

        return steps
    }

    /**
     * Covered Query 판단
     *
     * 인덱스만으로 쿼리를 완전히 처리할 수 있는지 확인합니다.
     *
     * 조건:
     * 1. WHERE 절의 컬럼이 인덱스의 leading column이어야 함
     * 2. SELECT 절의 모든 컬럼이 인덱스에 포함되어야 함
     *
     * 예:
     * - INDEX: (name, email, age)
     * - SELECT name, email FROM users WHERE name = 'Alice' → COVERED ✅
     * - SELECT name, email, address FROM users WHERE name = 'Alice' → NOT COVERED ❌ (address 없음)
     */
    private fun isCoveredQuery(
        indexColumns: List<String>,
        selectColumns: List<String>,
        whereColumn: String?
    ): Boolean {
        // 1. WHERE 절 컬럼이 leading column인지 확인
        if (whereColumn != null && indexColumns.firstOrNull() != whereColumn) {
            return false
        }

        // 2. SELECT 절의 모든 컬럼이 인덱스에 포함되어 있는지 확인
        return selectColumns.all { selectCol ->
            indexColumns.any { it.equals(selectCol, ignoreCase = true) }
        }
    }

    /**
     * INDEX_SCAN 비용 계산
     *
     * 비용 = log(totalRows) * selectivity * totalRows
     */
    private fun calculateIndexScanCost(totalRows: Long, selectivity: Double): Double {
        if (totalRows == 0L) return 0.0

        // 인덱스 탐색 비용 + 실제 데이터 읽기 비용
        val indexSeekCost = kotlin.math.log2(totalRows.toDouble())
        val dataReadCost = totalRows * selectivity

        return indexSeekCost + dataReadCost
    }

    /**
     * 설명 문구 생성
     */
    private fun buildDescription(
        stepType: String,
        decision: IndexScanDecision,
        selectivity: Double
    ): String {
        val selectivityPercent = "%.2f".format(selectivity * 100)

        return when (stepType) {
            "COVERED_INDEX_SCAN" -> {
                "Using covering index '${decision.selectedIndex?.indexName}' (selectivity: ${selectivityPercent}%). " +
                        "All columns can be retrieved from index without accessing table data. ✅ VERY EFFICIENT"
            }
            "INDEX_SCAN" -> {
                "Using index '${decision.selectedIndex?.indexName}' (selectivity: ${selectivityPercent}%). " +
                        decision.reason
            }
            else -> decision.reason
        }
    }

    /**
     * 쿼리 해시 생성 (캐싱용)
     */
    private fun generateQueryHash(sql: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(sql.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 저장된 QueryPlan 조회
     */
    fun getQueryPlan(planId: String): QueryPlan? {
        return try {
            queryPlanRepository.findById(planId).orElse(null)
        } catch (e: Exception) {
            logger.error("Failed to get query plan: $planId", e)
            null
        }
    }

    /**
     * 쿼리 해시로 QueryPlan 조회 (캐시 조회)
     */
    fun getQueryPlanByHash(queryHash: String): QueryPlan? {
        return try {
            queryPlanRepository.findByQueryHash(queryHash)
        } catch (e: Exception) {
            logger.error("Failed to get query plan by hash: $queryHash", e)
            null
        }
    }
}
