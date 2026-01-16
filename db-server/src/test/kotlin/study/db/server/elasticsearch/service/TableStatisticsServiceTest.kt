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
import study.db.server.elasticsearch.document.ColumnStatistics
import study.db.server.elasticsearch.document.TableStatistics
import study.db.server.elasticsearch.repository.TableStatisticsRepository

/**
 * TableStatisticsService 단위 테스트
 *
 * Mockito를 사용하여 Repository를 모킹하고 Service 로직만 테스트합니다.
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("TableStatisticsService 테스트")
class TableStatisticsServiceTest {

    @Mock
    private lateinit var tableStatisticsRepository: TableStatisticsRepository

    @InjectMocks
    private lateinit var tableStatisticsService: TableStatisticsService

    @Nested
    @DisplayName("통계 초기화 테스트")
    inner class InitializeStatisticsTest {

        @Test
        @DisplayName("새 테이블 통계 초기화")
        fun `initializes statistics for new table`() {
            // Given
            val tableName = "users"
            val columns = listOf("id", "name", "email")

            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.initializeStatistics(tableName, columns)

            // Then
            assertNotNull(result)
            assertEquals(tableName, result.tableName)
            assertEquals(0L, result.totalRows)
            assertEquals(3, result.columnStats.size)

            result.columnStats.forEach { colStat ->
                assertEquals(0L, colStat.distinctCount)
                assertEquals(0L, colStat.nullCount)
            }

            verify(tableStatisticsRepository).save(any(TableStatistics::class.java))
        }
    }

    @Nested
    @DisplayName("총 행 수 업데이트 테스트")
    inner class UpdateTotalRowsTest {

        @Test
        @DisplayName("INSERT 시 행 수 증가")
        fun `increments total rows on insert`() {
            // Given
            val tableName = "users"
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 100,
                columnStats = emptyList()
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateTotalRows(tableName, 10)

            // Then
            assertNotNull(result)
            assertEquals(110L, result?.totalRows)
        }

        @Test
        @DisplayName("DELETE 시 행 수 감소")
        fun `decrements total rows on delete`() {
            // Given
            val tableName = "users"
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 100,
                columnStats = emptyList()
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateTotalRows(tableName, -50)

            // Then
            assertNotNull(result)
            assertEquals(50L, result?.totalRows)
        }

        @Test
        @DisplayName("행 수가 음수가 되지 않도록 보정")
        fun `prevents negative total rows`() {
            // Given
            val tableName = "users"
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 10,
                columnStats = emptyList()
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateTotalRows(tableName, -20)

            // Then
            assertNotNull(result)
            assertEquals(0L, result?.totalRows)
        }
    }

    @Nested
    @DisplayName("컬럼 통계 업데이트 테스트")
    inner class UpdateColumnStatisticsTest {

        @Test
        @DisplayName("특정 컬럼의 distinctCount 증가")
        fun `increments distinct count for column`() {
            // Given
            val tableName = "users"
            val columnName = "email"
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 100,
                columnStats = listOf(
                    ColumnStatistics(columnName = "id", distinctCount = 100),
                    ColumnStatistics(columnName = "email", distinctCount = 95),
                    ColumnStatistics(columnName = "name", distinctCount = 80)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateColumnStatistics(tableName, columnName, 1)

            // Then
            assertNotNull(result)
            val emailStats = result?.columnStats?.find { it.columnName == "email" }
            assertEquals(96L, emailStats?.distinctCount)
        }

        @Test
        @DisplayName("NULL 값 개수 증가")
        fun `increments null count for column`() {
            // Given
            val tableName = "users"
            val columnName = "email"
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 100,
                columnStats = listOf(
                    ColumnStatistics(columnName = "email", distinctCount = 95, nullCount = 5)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateColumnStatistics(
                tableName, columnName,
                distinctCountDelta = 0,
                nullCountDelta = 1
            )

            // Then
            assertNotNull(result)
            val emailStats = result?.columnStats?.find { it.columnName == "email" }
            assertEquals(6L, emailStats?.nullCount)
        }
    }

    @Nested
    @DisplayName("INSERT 시 통계 업데이트 테스트")
    inner class UpdateStatisticsOnInsertTest {

        @Test
        @DisplayName("데이터 삽입 시 통계 업데이트")
        fun `updates statistics on data insert`() {
            // Given
            val tableName = "users"
            val insertedData = mapOf(
                "id" to "1",
                "name" to "Alice",
                "email" to "alice@example.com"
            )
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 10,
                columnStats = listOf(
                    ColumnStatistics(columnName = "id", distinctCount = 10),
                    ColumnStatistics(columnName = "name", distinctCount = 8),
                    ColumnStatistics(columnName = "email", distinctCount = 9)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateStatisticsOnInsert(tableName, insertedData)

            // Then
            assertNotNull(result)
            assertEquals(11L, result?.totalRows)

            val emailStats = result?.columnStats?.find { it.columnName == "email" }
            assertEquals(10L, emailStats?.distinctCount)
        }

        @Test
        @DisplayName("NULL 값 삽입 시 nullCount 증가")
        fun `increments null count on null value insert`() {
            // Given
            val tableName = "users"
            val insertedData = mapOf(
                "id" to "1",
                "name" to "Alice"
                // email은 NULL
            )
            val currentStats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 10,
                columnStats = listOf(
                    ColumnStatistics(columnName = "id", distinctCount = 10),
                    ColumnStatistics(columnName = "name", distinctCount = 8),
                    ColumnStatistics(columnName = "email", distinctCount = 9, nullCount = 1)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(currentStats)
            `when`(tableStatisticsRepository.save(any(TableStatistics::class.java)))
                .thenAnswer { it.arguments[0] as TableStatistics }

            // When
            val result = tableStatisticsService.updateStatisticsOnInsert(tableName, insertedData)

            // Then
            assertNotNull(result)
            val emailStats = result?.columnStats?.find { it.columnName == "email" }
            assertEquals(2L, emailStats?.nullCount)
        }
    }

    @Nested
    @DisplayName("Selectivity 계산 테스트")
    inner class CalculateSelectivityTest {

        @Test
        @DisplayName("높은 카디널리티 - 낮은 selectivity")
        fun `calculates low selectivity for high cardinality`() {
            // Given: email 컬럼 (distinctCount = 1000)
            val tableName = "users"
            val columnName = "email"
            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "email", distinctCount = 1000)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(stats)

            // When
            val selectivity = tableStatisticsService.calculateSelectivity(tableName, columnName)

            // Then: 1/1000 = 0.001 (0.1%)
            assertEquals(0.001, selectivity, 0.0001)
        }

        @Test
        @DisplayName("낮은 카디널리티 - 높은 selectivity")
        fun `calculates high selectivity for low cardinality`() {
            // Given: gender 컬럼 (distinctCount = 2)
            val tableName = "users"
            val columnName = "gender"
            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "gender", distinctCount = 2)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(stats)

            // When
            val selectivity = tableStatisticsService.calculateSelectivity(tableName, columnName)

            // Then: 1/2 = 0.5 (50%)
            assertEquals(0.5, selectivity, 0.0001)
        }

        @Test
        @DisplayName("통계 없을 때 최악의 경우로 가정")
        fun `returns worst case when statistics not found`() {
            // Given
            val tableName = "non_existent"
            val columnName = "column"

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(null)

            // When
            val selectivity = tableStatisticsService.calculateSelectivity(tableName, columnName)

            // Then: 1.0 (100% - 최악의 경우)
            assertEquals(1.0, selectivity)
        }
    }

    @Nested
    @DisplayName("통계 조회 테스트")
    inner class GetStatisticsTest {

        @Test
        @DisplayName("테이블 통계 조회")
        fun `retrieves table statistics`() {
            // Given
            val tableName = "users"
            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = emptyList()
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(stats)

            // When
            val result = tableStatisticsService.getStatistics(tableName)

            // Then
            assertNotNull(result)
            assertEquals(tableName, result?.tableName)
            assertEquals(1000L, result?.totalRows)
        }

        @Test
        @DisplayName("컬럼 통계 조회")
        fun `retrieves column statistics`() {
            // Given
            val tableName = "users"
            val columnName = "email"
            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = listOf(
                    ColumnStatistics(columnName = "email", distinctCount = 950)
                )
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(stats)

            // When
            val result = tableStatisticsService.getColumnStatistics(tableName, columnName)

            // Then
            assertNotNull(result)
            assertEquals("email", result?.columnName)
            assertEquals(950L, result?.distinctCount)
        }
    }

    @Nested
    @DisplayName("통계 삭제 테스트")
    inner class DeleteStatisticsTest {

        @Test
        @DisplayName("테이블 통계 삭제 성공")
        fun `deletes table statistics successfully`() {
            // Given
            val tableName = "users"
            val stats = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 1000,
                columnStats = emptyList()
            )

            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(stats)
            doNothing().`when`(tableStatisticsRepository).delete(stats)

            // When
            val result = tableStatisticsService.deleteStatistics(tableName)

            // Then
            assertTrue(result)
            verify(tableStatisticsRepository).delete(stats)
        }

        @Test
        @DisplayName("존재하지 않는 통계 삭제 시 false 반환")
        fun `returns false when statistics not found`() {
            // Given
            val tableName = "non_existent"
            `when`(tableStatisticsRepository.findByTableName(tableName)).thenReturn(null)

            // When
            val result = tableStatisticsService.deleteStatistics(tableName)

            // Then
            assertFalse(result)
            verify(tableStatisticsRepository, never()).delete(any(TableStatistics::class.java))
        }
    }
}
