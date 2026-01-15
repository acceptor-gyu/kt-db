package study.db.server.elasticsearch.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import study.db.server.db_engine.SqlParser
import study.db.server.db_engine.dto.ParsedQuery
import study.db.server.db_engine.dto.WhereCondition
import study.db.server.elasticsearch.document.*
import study.db.server.elasticsearch.repository.QueryPlanRepository

/**
 * ExplainService 단위 테스트
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExplainService 테스트")
class ExplainServiceTest {

    @Mock
    private lateinit var sqlParser: SqlParser

    @Mock
    private lateinit var tableMetadataService: TableMetadataService

    @Mock
    private lateinit var indexMetadataService: IndexMetadataService

    @Mock
    private lateinit var tableStatisticsService: TableStatisticsService

    @Mock
    private lateinit var queryPlanRepository: QueryPlanRepository

    @InjectMocks
    private lateinit var explainService: ExplainService

    @Nested
    @DisplayName("EXPLAIN - INDEX_SCAN 테스트")
    inner class ExplainIndexScanTest {

        @Test
        @DisplayName("인덱스가 있고 selectivity가 낮은 경우 INDEX_SCAN 사용")
        fun `uses index scan for low selectivity query`() {
            // Given
            val sql = "SELECT * FROM users WHERE email = 'alice@example.com'"
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT"),
                ColumnInfo("name", "VARCHAR"),
                ColumnInfo("email", "VARCHAR")
            )

            // SQL 파싱
            val parsedQuery = ParsedQuery(
                tableName = tableName,
                selectColumns = listOf("*"),
                whereConditions = listOf(WhereCondition("email", "=", "'alice@example.com'")),
                orderBy = emptyList()
            )
            `when`(sqlParser.parseQuery(sql)).thenReturn(parsedQuery)

            // 테이블 메타데이터
            `when`(tableMetadataService.tableExists(tableName)).thenReturn(true)
            `when`(tableMetadataService.getTableColumns(tableName)).thenReturn(columns)

            // 테이블 통계
            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "email", distinctCount = 950)
                )
            )
            `when`(tableStatisticsService.getStatistics(tableName)).thenReturn(stats)
            `when`(tableStatisticsService.calculateSelectivity(tableName, "email")).thenReturn(0.001)

            // 인덱스 메타데이터
            val index = IndexMetadata(
                indexId = "users_idx_email",
                indexName = "idx_email",
                tableName = tableName,
                indexColumns = listOf("email"),
                status = IndexStatus.ACTIVE
            )
            val decision = IndexScanDecision(
                useIndex = true,
                reason = "Low selectivity",
                scanType = ScanType.INDEX_SCAN,
                selectedIndex = index,
                estimatedSelectivity = 0.001
            )
            `when`(indexMetadataService.shouldUseIndexScan(tableName, "email", 0.001))
                .thenReturn(decision)

            // QueryPlan 저장
            `when`(queryPlanRepository.save(any(QueryPlan::class.java)))
                .thenAnswer { it.arguments[0] as QueryPlan }

            // When
            val result = explainService.explain(sql)

            // Then
            assertNotNull(result)
            assertEquals(1, result.executionSteps.size)

            val step = result.executionSteps[0]
            assertEquals("INDEX_SCAN", step.stepType)
            assertEquals("idx_email", step.indexUsed)
            assertFalse(result.isCoveredQuery)
        }
    }

    @Nested
    @DisplayName("EXPLAIN - TABLE_SCAN 테스트")
    inner class ExplainTableScanTest {

        @Test
        @DisplayName("인덱스가 없는 경우 TABLE_SCAN 사용")
        fun `uses table scan when no index available`() {
            // Given
            val sql = "SELECT * FROM users WHERE age = 25"
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT"),
                ColumnInfo("name", "VARCHAR"),
                ColumnInfo("age", "INT")
            )

            // SQL 파싱
            val parsedQuery = ParsedQuery(
                tableName = tableName,
                selectColumns = listOf("*"),
                whereConditions = listOf(WhereCondition("age", "=", "25")),
                orderBy = emptyList()
            )
            `when`(sqlParser.parseQuery(sql)).thenReturn(parsedQuery)

            `when`(tableMetadataService.tableExists(tableName)).thenReturn(true)
            `when`(tableMetadataService.getTableColumns(tableName)).thenReturn(columns)

            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "age", distinctCount = 50)
                )
            )
            `when`(tableStatisticsService.getStatistics(tableName)).thenReturn(stats)
            `when`(tableStatisticsService.calculateSelectivity(tableName, "age")).thenReturn(0.02)

            val decision = IndexScanDecision(
                useIndex = false,
                reason = "No index available",
                scanType = ScanType.TABLE_SCAN
            )
            `when`(indexMetadataService.shouldUseIndexScan(tableName, "age", 0.02))
                .thenReturn(decision)

            `when`(queryPlanRepository.save(any(QueryPlan::class.java)))
                .thenAnswer { it.arguments[0] as QueryPlan }

            // When
            val result = explainService.explain(sql)

            // Then
            assertNotNull(result)
            assertEquals(1, result.executionSteps.size)

            val step = result.executionSteps[0]
            assertEquals("TABLE_SCAN", step.stepType)
            assertNull(step.indexUsed)
        }

        @Test
        @DisplayName("WHERE 절이 없는 경우 Full TABLE_SCAN")
        fun `uses full table scan when no where clause`() {
            // Given
            val sql = "SELECT * FROM users"
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT"),
                ColumnInfo("name", "VARCHAR")
            )

            // SQL 파싱
            val parsedQuery = ParsedQuery(
                tableName = tableName,
                selectColumns = listOf("*"),
                whereConditions = emptyList(),
                orderBy = emptyList()
            )
            `when`(sqlParser.parseQuery(sql)).thenReturn(parsedQuery)

            `when`(tableMetadataService.tableExists(tableName)).thenReturn(true)
            `when`(tableMetadataService.getTableColumns(tableName)).thenReturn(columns)

            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = emptyList()
            )
            `when`(tableStatisticsService.getStatistics(tableName)).thenReturn(stats)

            `when`(queryPlanRepository.save(any(QueryPlan::class.java)))
                .thenAnswer { it.arguments[0] as QueryPlan }

            // When
            val result = explainService.explain(sql)

            // Then
            assertNotNull(result)
            assertEquals(1, result.executionSteps.size)

            val step = result.executionSteps[0]
            assertEquals("TABLE_SCAN", step.stepType)
            assertNull(step.indexUsed)
            assertEquals(1000L, step.estimatedRows)
        }
    }

    @Nested
    @DisplayName("EXPLAIN - COVERED_INDEX_SCAN 테스트")
    inner class ExplainCoveredIndexScanTest {

        @Test
        @DisplayName("Covered Query - 모든 컬럼이 인덱스에 포함된 경우")
        fun `detects covered query when all columns in index`() {
            // Given: 복합 인덱스 (name, email)가 있고, SELECT name, email만 조회
            val sql = "SELECT name, email FROM users WHERE name = 'Alice'"
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT"),
                ColumnInfo("name", "VARCHAR"),
                ColumnInfo("email", "VARCHAR")
            )

            // SQL 파싱
            val parsedQuery = ParsedQuery(
                tableName = tableName,
                selectColumns = listOf("name", "email"),
                whereConditions = listOf(WhereCondition("name", "=", "'Alice'")),
                orderBy = emptyList()
            )
            `when`(sqlParser.parseQuery(sql)).thenReturn(parsedQuery)

            `when`(tableMetadataService.tableExists(tableName)).thenReturn(true)
            `when`(tableMetadataService.getTableColumns(tableName)).thenReturn(columns)

            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "name", distinctCount = 800)
                )
            )
            `when`(tableStatisticsService.getStatistics(tableName)).thenReturn(stats)
            `when`(tableStatisticsService.calculateSelectivity(tableName, "name")).thenReturn(0.00125)

            // 복합 인덱스: (name, email)
            val index = IndexMetadata(
                indexId = "users_idx_name_email",
                indexName = "idx_name_email",
                tableName = tableName,
                indexColumns = listOf("name", "email"),
                status = IndexStatus.ACTIVE
            )
            val decision = IndexScanDecision(
                useIndex = true,
                reason = "Low selectivity",
                scanType = ScanType.INDEX_SCAN,
                selectedIndex = index,
                estimatedSelectivity = 0.00125
            )
            `when`(indexMetadataService.shouldUseIndexScan(tableName, "name", 0.00125))
                .thenReturn(decision)

            `when`(queryPlanRepository.save(any(QueryPlan::class.java)))
                .thenAnswer { it.arguments[0] as QueryPlan }

            // When
            val result = explainService.explain(sql)

            // Then
            assertNotNull(result)
            assertTrue(result.isCoveredQuery, "Should be a covered query")

            val step = result.executionSteps[0]
            assertEquals("COVERED_INDEX_SCAN", step.stepType)
            assertTrue(step.isCovered)
            assertTrue(step.description.contains("covering index"))
        }

        @Test
        @DisplayName("Not Covered Query - 인덱스에 없는 컬럼 조회")
        fun `not covered when selecting columns not in index`() {
            // Given: 인덱스 (name)만 있고, SELECT name, age 조회 (age는 인덱스에 없음)
            val sql = "SELECT name, age FROM users WHERE name = 'Alice'"
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT"),
                ColumnInfo("name", "VARCHAR"),
                ColumnInfo("age", "INT")
            )

            // SQL 파싱
            val parsedQuery = ParsedQuery(
                tableName = tableName,
                selectColumns = listOf("name", "age"),
                whereConditions = listOf(WhereCondition("name", "=", "'Alice'")),
                orderBy = emptyList()
            )
            `when`(sqlParser.parseQuery(sql)).thenReturn(parsedQuery)

            `when`(tableMetadataService.tableExists(tableName)).thenReturn(true)
            `when`(tableMetadataService.getTableColumns(tableName)).thenReturn(columns)

            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "name", distinctCount = 800)
                )
            )
            `when`(tableStatisticsService.getStatistics(tableName)).thenReturn(stats)
            `when`(tableStatisticsService.calculateSelectivity(tableName, "name")).thenReturn(0.00125)

            // 단일 인덱스: (name)만
            val index = IndexMetadata(
                indexId = "users_idx_name",
                indexName = "idx_name",
                tableName = tableName,
                indexColumns = listOf("name"),
                status = IndexStatus.ACTIVE
            )
            val decision = IndexScanDecision(
                useIndex = true,
                reason = "Low selectivity",
                scanType = ScanType.INDEX_SCAN,
                selectedIndex = index,
                estimatedSelectivity = 0.00125
            )
            `when`(indexMetadataService.shouldUseIndexScan(tableName, "name", 0.00125))
                .thenReturn(decision)

            `when`(queryPlanRepository.save(any(QueryPlan::class.java)))
                .thenAnswer { it.arguments[0] as QueryPlan }

            // When
            val result = explainService.explain(sql)

            // Then
            assertNotNull(result)
            assertFalse(result.isCoveredQuery, "Should NOT be a covered query (age not in index)")

            val step = result.executionSteps[0]
            assertEquals("INDEX_SCAN", step.stepType)
            assertFalse(step.isCovered)
        }
    }

    @Nested
    @DisplayName("에러 처리 테스트")
    inner class ErrorHandlingTest {

        @Test
        @DisplayName("존재하지 않는 테이블 - 에러 발생")
        fun `throws error when table does not exist`() {
            // Given
            val sql = "SELECT * FROM non_existent WHERE id = 1"

            val parsedQuery = ParsedQuery(
                tableName = "non_existent",
                selectColumns = listOf("*"),
                whereConditions = listOf(WhereCondition("id", "=", "1")),
                orderBy = emptyList()
            )
            `when`(sqlParser.parseQuery(sql)).thenReturn(parsedQuery)
            `when`(tableMetadataService.tableExists("non_existent")).thenReturn(false)

            // When & Then
            val exception = assertThrows(IllegalStateException::class.java) {
                explainService.explain(sql)
            }

            assertTrue(exception.message!!.contains("Table doesn't exist"))
        }

        @Test
        @DisplayName("SELECT가 아닌 쿼리 - 에러 발생")
        fun `throws error for non-select query`() {
            // Given
            val sql = "INSERT INTO users VALUES (1, 'Alice')"

            `when`(sqlParser.parseQuery(sql)).thenThrow(ClassCastException("Not a SELECT statement"))

            // When & Then
            val exception = assertThrows(IllegalArgumentException::class.java) {
                explainService.explain(sql)
            }

            assertTrue(exception.message!!.contains("Failed to parse SQL"))
        }
    }
}
