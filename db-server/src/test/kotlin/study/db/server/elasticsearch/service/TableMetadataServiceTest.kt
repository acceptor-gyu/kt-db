package study.db.server.elasticsearch.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.jupiter.MockitoExtension
import study.db.server.elasticsearch.document.ColumnInfo
import study.db.server.elasticsearch.document.TableMetadata
import study.db.server.elasticsearch.document.TableStatus
import study.db.server.elasticsearch.repository.TableMetadataRepository
import java.time.Instant

/**
 * TableMetadataService 단위 테스트
 *
 * Mockito를 사용하여 Repository를 모킹하고 Service 로직만 테스트합니다.
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("TableMetadataService 테스트")
class TableMetadataServiceTest {

    @Mock
    private lateinit var tableMetadataRepository: TableMetadataRepository

    @InjectMocks
    private lateinit var tableMetadataService: TableMetadataService

    @Nested
    @DisplayName("테이블 생성 테스트")
    inner class CreateTableTest {

        @Test
        @DisplayName("새로운 테이블 메타데이터 생성")
        fun `creates new table metadata`() {
            // Given
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT", nullable = false, primaryKey = true),
                ColumnInfo("name", "VARCHAR", nullable = true)
            )

            `when`(tableMetadataRepository.findByTableName(tableName)).thenReturn(null)
            `when`(tableMetadataRepository.save(any(TableMetadata::class.java)))
                .thenAnswer { it.arguments[0] as TableMetadata }

            // When
            val result = tableMetadataService.createTable(tableName, columns)

            // Then
            assertNotNull(result)
            assertEquals(tableName, result.tableName)
            assertEquals(columns.size, result.columns.size)
            assertEquals(TableStatus.ACTIVE, result.status)
            assertEquals(0L, result.estimatedRowCount)

            verify(tableMetadataRepository).findByTableName(tableName)
            verify(tableMetadataRepository).save(any(TableMetadata::class.java))
        }

        @Test
        @DisplayName("이미 존재하는 테이블 생성 시 예외 발생")
        fun `throws exception when table already exists`() {
            // Given
            val tableName = "users"
            val columns = listOf(ColumnInfo("id", "INT"))
            val existingTable = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = columns,
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableName(tableName)).thenReturn(existingTable)

            // When & Then
            assertThrows(IllegalStateException::class.java) {
                tableMetadataService.createTable(tableName, columns)
            }

            verify(tableMetadataRepository).findByTableName(tableName)
            verify(tableMetadataRepository, never()).save(any(TableMetadata::class.java))
        }

        @Test
        @DisplayName("DROPPED 상태 테이블은 재생성 가능")
        fun `can recreate dropped table`() {
            // Given
            val tableName = "users"
            val columns = listOf(ColumnInfo("id", "INT"))
            val droppedTable = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = columns,
                status = TableStatus.DROPPED
            )

            `when`(tableMetadataRepository.findByTableName(tableName)).thenReturn(droppedTable)
            `when`(tableMetadataRepository.save(any(TableMetadata::class.java)))
                .thenAnswer { it.arguments[0] as TableMetadata }

            // When
            val result = tableMetadataService.createTable(tableName, columns)

            // Then
            assertNotNull(result)
            assertEquals(TableStatus.ACTIVE, result.status)
        }
    }

    @Nested
    @DisplayName("행 수 업데이트 테스트")
    inner class UpdateRowCountTest {

        @Test
        @DisplayName("INSERT 시 행 수 증가")
        fun `increments row count on insert`() {
            // Given
            val tableName = "users"
            val currentTable = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = listOf(ColumnInfo("id", "INT")),
                estimatedRowCount = 10,
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(currentTable)
            `when`(tableMetadataRepository.save(any(TableMetadata::class.java)))
                .thenAnswer { it.arguments[0] as TableMetadata }

            // When
            val result = tableMetadataService.updateRowCount(tableName, 5)

            // Then
            assertNotNull(result)
            assertEquals(15L, result?.estimatedRowCount)
        }

        @Test
        @DisplayName("DELETE 시 행 수 감소")
        fun `decrements row count on delete`() {
            // Given
            val tableName = "users"
            val currentTable = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = listOf(ColumnInfo("id", "INT")),
                estimatedRowCount = 10,
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(currentTable)
            `when`(tableMetadataRepository.save(any(TableMetadata::class.java)))
                .thenAnswer { it.arguments[0] as TableMetadata }

            // When
            val result = tableMetadataService.updateRowCount(tableName, -3)

            // Then
            assertNotNull(result)
            assertEquals(7L, result?.estimatedRowCount)
        }

        @Test
        @DisplayName("행 수가 음수가 되지 않도록 보정")
        fun `prevents negative row count`() {
            // Given
            val tableName = "users"
            val currentTable = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = listOf(ColumnInfo("id", "INT")),
                estimatedRowCount = 5,
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(currentTable)
            `when`(tableMetadataRepository.save(any(TableMetadata::class.java)))
                .thenAnswer { it.arguments[0] as TableMetadata }

            // When
            val result = tableMetadataService.updateRowCount(tableName, -10)

            // Then
            assertNotNull(result)
            assertEquals(0L, result?.estimatedRowCount)
        }

        @Test
        @DisplayName("존재하지 않는 테이블의 행 수 업데이트 시 null 반환")
        fun `returns null when table does not exist`() {
            // Given
            val tableName = "non_existent"
            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(null)

            // When
            val result = tableMetadataService.updateRowCount(tableName, 5)

            // Then
            assertNull(result)
            verify(tableMetadataRepository, never()).save(any(TableMetadata::class.java))
        }
    }

    @Nested
    @DisplayName("테이블 삭제 테스트")
    inner class DropTableTest {

        @Test
        @DisplayName("테이블 상태를 DROPPED로 변경")
        fun `changes table status to dropped`() {
            // Given
            val tableName = "users"
            val activeTable = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = listOf(ColumnInfo("id", "INT")),
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(activeTable)
            `when`(tableMetadataRepository.save(any(TableMetadata::class.java)))
                .thenAnswer { it.arguments[0] as TableMetadata }

            // When
            val result = tableMetadataService.dropTable(tableName)

            // Then
            assertNotNull(result)
            assertEquals(TableStatus.DROPPED, result?.status)
        }

        @Test
        @DisplayName("존재하지 않는 테이블 삭제 시 null 반환")
        fun `returns null when table does not exist`() {
            // Given
            val tableName = "non_existent"
            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(null)

            // When
            val result = tableMetadataService.dropTable(tableName)

            // Then
            assertNull(result)
            verify(tableMetadataRepository, never()).save(any(TableMetadata::class.java))
        }
    }

    @Nested
    @DisplayName("테이블 조회 테스트")
    inner class GetTableMetadataTest {

        @Test
        @DisplayName("활성 테이블 메타데이터 조회")
        fun `retrieves active table metadata`() {
            // Given
            val tableName = "users"
            val tableMetadata = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = listOf(ColumnInfo("id", "INT")),
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(tableMetadata)

            // When
            val result = tableMetadataService.getTableMetadata(tableName)

            // Then
            assertNotNull(result)
            assertEquals(tableName, result?.tableName)
        }

        @Test
        @DisplayName("모든 활성 테이블 조회")
        fun `retrieves all active tables`() {
            // Given
            val tables = listOf(
                TableMetadata("users", "users", listOf(ColumnInfo("id", "INT")), status = TableStatus.ACTIVE),
                TableMetadata("posts", "posts", listOf(ColumnInfo("id", "INT")), status = TableStatus.ACTIVE)
            )

            `when`(tableMetadataRepository.findByStatus(TableStatus.ACTIVE)).thenReturn(tables)

            // When
            val result = tableMetadataService.getAllActiveTables()

            // Then
            assertEquals(2, result.size)
        }
    }

    @Nested
    @DisplayName("테이블 존재 확인 테스트")
    inner class TableExistsTest {

        @Test
        @DisplayName("활성 테이블 존재 확인")
        fun `returns true when table exists`() {
            // Given
            val tableName = "users"
            val tableMetadata = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = listOf(ColumnInfo("id", "INT")),
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(tableMetadata)

            // When
            val result = tableMetadataService.tableExists(tableName)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("테이블 미존재 확인")
        fun `returns false when table does not exist`() {
            // Given
            val tableName = "non_existent"
            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(null)

            // When
            val result = tableMetadataService.tableExists(tableName)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("컬럼 정보 조회 테스트")
    inner class GetTableColumnsTest {

        @Test
        @DisplayName("테이블의 컬럼 정보 조회")
        fun `retrieves table columns`() {
            // Given
            val tableName = "users"
            val columns = listOf(
                ColumnInfo("id", "INT", nullable = false, primaryKey = true),
                ColumnInfo("name", "VARCHAR", nullable = true)
            )
            val tableMetadata = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = columns,
                status = TableStatus.ACTIVE
            )

            `when`(tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE))
                .thenReturn(tableMetadata)

            // When
            val result = tableMetadataService.getTableColumns(tableName)

            // Then
            assertNotNull(result)
            assertEquals(2, result?.size)
            assertEquals("id", result?.get(0)?.name)
            assertEquals("name", result?.get(1)?.name)
        }
    }
}
