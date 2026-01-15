package study.db.server.elasticsearch

import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import study.db.server.elasticsearch.document.*
import study.db.server.elasticsearch.service.ExplainService
import study.db.server.elasticsearch.service.IndexMetadataService
import study.db.server.elasticsearch.service.TableMetadataService
import study.db.server.elasticsearch.service.TableStatisticsService
import study.db.server.elasticsearch.repository.TableStatisticsRepository
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * EXPLAIN 기능 통합 테스트
 *
 * 실제 Elasticsearch와 연동하여 EXPLAIN 명령의 전체 흐름을 테스트합니다.
 */
@SpringBootTest(classes = [study.db.server.elasticsearch.InitElasticsearchIndexApp::class])
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("EXPLAIN Integration Test")
class ExplainIntegrationTest {

    @Autowired
    private lateinit var explainService: ExplainService

    @Autowired
    private lateinit var tableMetadataService: TableMetadataService

    @Autowired
    private lateinit var indexMetadataService: IndexMetadataService

    @Autowired
    private lateinit var tableStatisticsService: TableStatisticsService

    @Autowired
    private lateinit var tableStatisticsRepository: TableStatisticsRepository

    /**
     * 테스트 데이터 초기화
     */
    @BeforeAll
    fun setupTestData() {
        // users 테이블 생성
        val columns = listOf(
            ColumnInfo("id", "INT"),
            ColumnInfo("name", "VARCHAR"),
            ColumnInfo("email", "VARCHAR"),
            ColumnInfo("age", "INT")
        )
        tableMetadataService.createTable("users", columns)
        tableMetadataService.updateRowCount("users", 10_000)

        // 인덱스 생성
        indexMetadataService.createIndex(
            indexName = "idx_email",
            tableName = "users",
            columns = listOf("email"),
            unique = false
        )
        indexMetadataService.createIndex(
            indexName = "idx_name_email",
            tableName = "users",
            columns = listOf("name", "email"),
            unique = false
        )

        // 통계 생성
        val stats = TableStatistics(
            statsId = "users",
            tableName = "users",
            totalRows = 10_000,
            columnStats = listOf(
                ColumnStatistics("email", distinctCount = 9_900, nullCount = 0),
                ColumnStatistics("name", distinctCount = 5_000, nullCount = 0),
                ColumnStatistics("age", distinctCount = 60, nullCount = 0)
            ),
            updatedAt = java.time.Instant.now()
        )
        tableStatisticsRepository.save(stats)

        // Elasticsearch 인덱싱 대기
        Thread.sleep(1000)
    }

    @Nested
    @DisplayName("INDEX_SCAN Tests")
    inner class IndexScanTests {

        @Test
        @DisplayName("Low selectivity should use INDEX_SCAN")
        fun testLowSelectivityIndexScan() {
            // Given
            val sql = "SELECT * FROM users WHERE email = 'test@example.com'"

            // When
            val plan = explainService.explain(sql)

            // Then
            assertNotNull(plan)
            assertEquals(1, plan.executionSteps.size)

            val step = plan.executionSteps[0]
            assertEquals("INDEX_SCAN", step.stepType)
            assertEquals("idx_email", step.indexUsed)
            assertFalse(step.isCovered)
            assertFalse(plan.isCoveredQuery)
        }

        @Test
        @DisplayName("High selectivity should use TABLE_SCAN even with index")
        fun testHighSelectivityTableScan() {
            // Given
            val sql = "SELECT * FROM users WHERE age = 30"

            // When
            val plan = explainService.explain(sql)

            // Then
            assertNotNull(plan)
            assertEquals(1, plan.executionSteps.size)

            val step = plan.executionSteps[0]
            // age의 selectivity = 1/60 ≈ 1.67% 이지만,
            // 실제로는 인덱스가 없어서 TABLE_SCAN이 선택됨
            assertEquals("TABLE_SCAN", step.stepType)
            assertEquals(null, step.indexUsed)
        }
    }

    @Nested
    @DisplayName("COVERED_INDEX_SCAN Tests")
    inner class CoveredIndexScanTests {

        @Test
        @DisplayName("Covered query should use COVERED_INDEX_SCAN")
        fun testCoveredQuery() {
            // Given: idx_name_email에 모든 SELECT 컬럼이 포함됨
            val sql = "SELECT name, email FROM users WHERE name = 'Alice'"

            // When
            val plan = explainService.explain(sql)

            // Then
            assertNotNull(plan)
            assertEquals(1, plan.executionSteps.size)

            val step = plan.executionSteps[0]
            assertEquals("COVERED_INDEX_SCAN", step.stepType)
            assertEquals("idx_name_email", step.indexUsed)
            assertTrue(step.isCovered)
            assertTrue(plan.isCoveredQuery)
            assertTrue(step.description.contains("covering index"))
        }

        @Test
        @DisplayName("Non-covered query should use INDEX_SCAN")
        fun testNonCoveredQuery() {
            // Given: age가 idx_name_email에 없음
            val sql = "SELECT name, email, age FROM users WHERE name = 'Alice'"

            // When
            val plan = explainService.explain(sql)

            // Then
            assertNotNull(plan)
            assertEquals(1, plan.executionSteps.size)

            val step = plan.executionSteps[0]
            assertEquals("INDEX_SCAN", step.stepType)
            assertEquals("idx_name_email", step.indexUsed)
            assertFalse(step.isCovered)
            assertFalse(plan.isCoveredQuery)
        }
    }

    @Nested
    @DisplayName("TABLE_SCAN Tests")
    inner class TableScanTests {

        @Test
        @DisplayName("No WHERE clause should use full TABLE_SCAN")
        fun testFullTableScan() {
            // Given
            val sql = "SELECT * FROM users"

            // When
            val plan = explainService.explain(sql)

            // Then
            assertNotNull(plan)
            assertEquals(1, plan.executionSteps.size)

            val step = plan.executionSteps[0]
            assertEquals("TABLE_SCAN", step.stepType)
            assertEquals(null, step.indexUsed)
            assertEquals(null, step.filterCondition)
            assertEquals(10_000L, step.estimatedRows)
        }
    }

    @Nested
    @DisplayName("Cost Estimation Tests")
    inner class CostEstimationTests {

        @Test
        @DisplayName("INDEX_SCAN should have lower cost than TABLE_SCAN")
        fun testIndexScanCostLowerThanTableScan() {
            // Given
            val indexScanSql = "SELECT * FROM users WHERE email = 'test@example.com'"
            val tableScanSql = "SELECT * FROM users"

            // When
            val indexPlan = explainService.explain(indexScanSql)
            val tablePlan = explainService.explain(tableScanSql)

            // Then
            assertTrue(indexPlan.estimatedCost < tablePlan.estimatedCost,
                "INDEX_SCAN cost (${indexPlan.estimatedCost}) should be lower than TABLE_SCAN cost (${tablePlan.estimatedCost})")
        }

        @Test
        @DisplayName("Estimated rows should reflect selectivity")
        fun testEstimatedRowsReflectSelectivity() {
            // Given
            val sql = "SELECT * FROM users WHERE email = 'test@example.com'"

            // When
            val plan = explainService.explain(sql)

            // Then
            // Selectivity = 1/9900 ≈ 0.0001
            // Estimated rows = 10,000 * 0.0001 ≈ 1
            assertTrue(plan.estimatedRows < 100,
                "Estimated rows should be small for low selectivity query")
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    inner class ErrorHandlingTests {

        @Test
        @DisplayName("Non-existent table should throw exception")
        fun testNonExistentTable() {
            // Given
            val sql = "SELECT * FROM non_existent_table WHERE id = 1"

            // When & Then
            val exception = assertThrows<IllegalStateException> {
                explainService.explain(sql)
            }

            assertTrue(exception.message!!.contains("Table doesn't exist"))
        }

        @Test
        @DisplayName("Invalid SQL should throw exception")
        fun testInvalidSql() {
            // Given
            val sql = "INVALID SQL SYNTAX"

            // When & Then
            assertThrows<IllegalArgumentException> {
                explainService.explain(sql)
            }
        }

        @Test
        @DisplayName("Non-SELECT query should throw exception")
        fun testNonSelectQuery() {
            // Given
            val sql = "INSERT INTO users VALUES (1, 'Alice', 'alice@example.com', 25)"

            // When & Then
            assertThrows<IllegalArgumentException> {
                explainService.explain(sql)
            }
        }
    }

    @Nested
    @DisplayName("Query Plan Persistence Tests")
    inner class QueryPlanPersistenceTests {

        @Test
        @DisplayName("Generated plan should be saved to Elasticsearch")
        fun testPlanPersistence() {
            // Given
            val sql = "SELECT * FROM users WHERE email = 'test@example.com'"

            // When
            val plan = explainService.explain(sql)

            // Elasticsearch 인덱싱 대기
            Thread.sleep(500)

            val retrieved = explainService.getQueryPlan(plan.planId)

            // Then
            assertNotNull(retrieved)
            assertEquals(plan.planId, retrieved.planId)
            assertEquals(plan.queryText, retrieved.queryText)
            assertEquals(plan.queryHash, retrieved.queryHash)
        }

        @Test
        @DisplayName("Same query should generate same hash")
        fun testQueryHashConsistency() {
            // Given
            val sql = "SELECT * FROM users WHERE email = 'test@example.com'"

            // When
            val plan1 = explainService.explain(sql)
            val plan2 = explainService.explain(sql)

            // Then
            assertEquals(plan1.queryHash, plan2.queryHash)
        }
    }
}
