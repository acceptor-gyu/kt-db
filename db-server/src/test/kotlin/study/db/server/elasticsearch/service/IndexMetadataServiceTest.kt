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
import study.db.server.elasticsearch.document.IndexMetadata
import study.db.server.elasticsearch.document.IndexStatus
import study.db.server.elasticsearch.document.IndexType
import study.db.server.elasticsearch.repository.IndexMetadataRepository

/**
 * IndexMetadataService 단위 테스트
 *
 * Mockito를 사용하여 Repository를 모킹하고 Service 로직만 테스트합니다.
 */
@ExtendWith(MockitoExtension::class)
@DisplayName("IndexMetadataService 테스트")
class IndexMetadataServiceTest {

    @Mock
    private lateinit var indexMetadataRepository: IndexMetadataRepository

    @InjectMocks
    private lateinit var indexMetadataService: IndexMetadataService

    @Nested
    @DisplayName("인덱스 생성 테스트")
    inner class CreateIndexTest {

        @Test
        @DisplayName("새로운 인덱스 메타데이터 생성")
        fun `creates new index metadata`() {
            // Given
            val indexName = "idx_users_name"
            val tableName = "users"
            val columns = listOf("name")

            `when`(indexMetadataRepository.findByIndexName(indexName)).thenReturn(null)
            `when`(indexMetadataRepository.save(any(IndexMetadata::class.java)))
                .thenAnswer { it.arguments[0] as IndexMetadata }

            // When
            val result = indexMetadataService.createIndex(indexName, tableName, columns)

            // Then
            assertNotNull(result)
            assertEquals(indexName, result.indexName)
            assertEquals(tableName, result.tableName)
            assertEquals(columns, result.indexColumns)
            assertEquals(IndexType.BTREE, result.indexType)
            assertEquals(false, result.unique)
            assertEquals(IndexStatus.ACTIVE, result.status)

            verify(indexMetadataRepository).findByIndexName(indexName)
            verify(indexMetadataRepository).save(any(IndexMetadata::class.java))
        }

        @Test
        @DisplayName("UNIQUE 인덱스 생성")
        fun `creates unique index`() {
            // Given
            val indexName = "idx_users_email"
            val tableName = "users"
            val columns = listOf("email")

            `when`(indexMetadataRepository.findByIndexName(indexName)).thenReturn(null)
            `when`(indexMetadataRepository.save(any(IndexMetadata::class.java)))
                .thenAnswer { it.arguments[0] as IndexMetadata }

            // When
            val result = indexMetadataService.createIndex(
                indexName, tableName, columns,
                indexType = IndexType.BTREE,
                unique = true
            )

            // Then
            assertTrue(result.unique)
        }

        @Test
        @DisplayName("복합 인덱스 생성")
        fun `creates composite index`() {
            // Given
            val indexName = "idx_users_name_email"
            val tableName = "users"
            val columns = listOf("name", "email")

            `when`(indexMetadataRepository.findByIndexName(indexName)).thenReturn(null)
            `when`(indexMetadataRepository.save(any(IndexMetadata::class.java)))
                .thenAnswer { it.arguments[0] as IndexMetadata }

            // When
            val result = indexMetadataService.createIndex(indexName, tableName, columns)

            // Then
            assertEquals(2, result.indexColumns.size)
            assertEquals("name", result.indexColumns[0])
            assertEquals("email", result.indexColumns[1])
        }

        @Test
        @DisplayName("이미 존재하는 인덱스 생성 시 예외 발생")
        fun `throws exception when index already exists`() {
            // Given
            val indexName = "idx_users_name"
            val tableName = "users"
            val columns = listOf("name")
            val existingIndex = IndexMetadata(
                indexId = "${tableName}_${indexName}",
                indexName = indexName,
                tableName = tableName,
                indexColumns = columns,
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByIndexName(indexName)).thenReturn(existingIndex)

            // When & Then
            assertThrows(IllegalStateException::class.java) {
                indexMetadataService.createIndex(indexName, tableName, columns)
            }

            verify(indexMetadataRepository).findByIndexName(indexName)
            verify(indexMetadataRepository, never()).save(any(IndexMetadata::class.java))
        }

        @Test
        @DisplayName("DROPPED 상태 인덱스는 재생성 가능")
        fun `can recreate dropped index`() {
            // Given
            val indexName = "idx_users_name"
            val tableName = "users"
            val columns = listOf("name")
            val droppedIndex = IndexMetadata(
                indexId = "${tableName}_${indexName}",
                indexName = indexName,
                tableName = tableName,
                indexColumns = columns,
                status = IndexStatus.DROPPED
            )

            `when`(indexMetadataRepository.findByIndexName(indexName)).thenReturn(droppedIndex)
            `when`(indexMetadataRepository.save(any(IndexMetadata::class.java)))
                .thenAnswer { it.arguments[0] as IndexMetadata }

            // When
            val result = indexMetadataService.createIndex(indexName, tableName, columns)

            // Then
            assertNotNull(result)
            assertEquals(IndexStatus.ACTIVE, result.status)
        }
    }

    @Nested
    @DisplayName("인덱스 삭제 테스트")
    inner class DropIndexTest {

        @Test
        @DisplayName("인덱스 상태를 DROPPED로 변경")
        fun `changes index status to dropped`() {
            // Given
            val indexName = "idx_users_name"
            val activeIndex = IndexMetadata(
                indexId = "users_idx_users_name",
                indexName = indexName,
                tableName = "users",
                indexColumns = listOf("name"),
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE))
                .thenReturn(activeIndex)
            `when`(indexMetadataRepository.save(any(IndexMetadata::class.java)))
                .thenAnswer { it.arguments[0] as IndexMetadata }

            // When
            val result = indexMetadataService.dropIndex(indexName)

            // Then
            assertNotNull(result)
            assertEquals(IndexStatus.DROPPED, result?.status)
        }

        @Test
        @DisplayName("존재하지 않는 인덱스 삭제 시 null 반환")
        fun `returns null when index does not exist`() {
            // Given
            val indexName = "non_existent"
            `when`(indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE))
                .thenReturn(null)

            // When
            val result = indexMetadataService.dropIndex(indexName)

            // Then
            assertNull(result)
            verify(indexMetadataRepository, never()).save(any(IndexMetadata::class.java))
        }
    }

    @Nested
    @DisplayName("인덱스 조회 테스트")
    inner class GetIndexMetadataTest {

        @Test
        @DisplayName("활성 인덱스 메타데이터 조회")
        fun `retrieves active index metadata`() {
            // Given
            val indexName = "idx_users_name"
            val indexMetadata = IndexMetadata(
                indexId = "users_idx_users_name",
                indexName = indexName,
                tableName = "users",
                indexColumns = listOf("name"),
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE))
                .thenReturn(indexMetadata)

            // When
            val result = indexMetadataService.getIndexMetadata(indexName)

            // Then
            assertNotNull(result)
            assertEquals(indexName, result?.indexName)
        }

        @Test
        @DisplayName("특정 테이블의 모든 인덱스 조회")
        fun `retrieves all indexes for table`() {
            // Given
            val tableName = "users"
            val indexes = listOf(
                IndexMetadata("users_idx1", "idx1", tableName, listOf("name"), status = IndexStatus.ACTIVE),
                IndexMetadata("users_idx2", "idx2", tableName, listOf("email"), status = IndexStatus.ACTIVE)
            )

            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(indexes)

            // When
            val result = indexMetadataService.getIndexesForTable(tableName)

            // Then
            assertEquals(2, result.size)
        }
    }

    @Nested
    @DisplayName("컬럼별 인덱스 조회 테스트 (EXPLAIN 핵심 기능)")
    inner class GetIndexesForColumnTest {

        @Test
        @DisplayName("특정 컬럼에 인덱스가 있는 경우")
        fun `returns indexes when column has index`() {
            // Given
            val tableName = "users"
            val columnName = "name"
            val indexes = listOf(
                IndexMetadata(
                    "users_idx_name",
                    "idx_name",
                    tableName,
                    listOf("name"),
                    status = IndexStatus.ACTIVE
                )
            )

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(indexes)

            // When
            val result = indexMetadataService.getIndexesForColumn(tableName, columnName)

            // Then
            assertEquals(1, result.size)
            assertTrue(result[0].indexColumns.contains("name"))
        }

        @Test
        @DisplayName("특정 컬럼에 인덱스가 없는 경우")
        fun `returns empty list when column has no index`() {
            // Given
            val tableName = "users"
            val columnName = "age"

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(emptyList())

            // When
            val result = indexMetadataService.getIndexesForColumn(tableName, columnName)

            // Then
            assertTrue(result.isEmpty())
        }

        @Test
        @DisplayName("복합 인덱스에 포함된 컬럼 조회")
        fun `returns composite index containing column`() {
            // Given
            val tableName = "users"
            val columnName = "name"
            val compositeIndex = IndexMetadata(
                "users_idx_composite",
                "idx_composite",
                tableName,
                listOf("name", "email"),
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(listOf(compositeIndex))

            // When
            val result = indexMetadataService.getIndexesForColumn(tableName, columnName)

            // Then
            assertEquals(1, result.size)
            assertEquals(2, result[0].indexColumns.size)
        }

        @Test
        @DisplayName("DROPPED 인덱스는 제외")
        fun `filters out dropped indexes`() {
            // Given
            val tableName = "users"
            val columnName = "name"
            val indexes = listOf(
                IndexMetadata("users_idx1", "idx1", tableName, listOf("name"), status = IndexStatus.ACTIVE),
                IndexMetadata("users_idx2", "idx2", tableName, listOf("name"), status = IndexStatus.DROPPED)
            )

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(indexes)

            // When
            val result = indexMetadataService.getIndexesForColumn(tableName, columnName)

            // Then
            assertEquals(1, result.size)
            assertEquals(IndexStatus.ACTIVE, result[0].status)
        }
    }

    @Nested
    @DisplayName("인덱스 존재 확인 테스트")
    inner class IndexExistsTest {

        @Test
        @DisplayName("활성 인덱스 존재 확인")
        fun `returns true when index exists`() {
            // Given
            val indexName = "idx_users_name"
            val indexMetadata = IndexMetadata(
                "users_idx_users_name",
                indexName,
                "users",
                listOf("name"),
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE))
                .thenReturn(indexMetadata)

            // When
            val result = indexMetadataService.indexExists(indexName)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("인덱스 미존재 확인")
        fun `returns false when index does not exist`() {
            // Given
            val indexName = "non_existent"
            `when`(indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE))
                .thenReturn(null)

            // When
            val result = indexMetadataService.indexExists(indexName)

            // Then
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("중복 인덱스 검사 테스트")
    inner class FindDuplicateIndexTest {

        @Test
        @DisplayName("같은 컬럼 구성의 인덱스 찾기")
        fun `finds duplicate index with same columns`() {
            // Given
            val tableName = "users"
            val columns = listOf("name", "email")
            val existingIndex = IndexMetadata(
                "users_idx1",
                "idx1",
                tableName,
                columns,
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(listOf(existingIndex))

            // When
            val result = indexMetadataService.findDuplicateIndex(tableName, columns)

            // Then
            assertNotNull(result)
            assertEquals("idx1", result?.indexName)
        }

        @Test
        @DisplayName("순서가 다른 인덱스는 중복 아님")
        fun `returns null for different column order`() {
            // Given
            val tableName = "users"
            val existingColumns = listOf("name", "email")
            val newColumns = listOf("email", "name")
            val existingIndex = IndexMetadata(
                "users_idx1",
                "idx1",
                tableName,
                existingColumns,
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(listOf(existingIndex))

            // When
            val result = indexMetadataService.findDuplicateIndex(tableName, newColumns)

            // Then
            assertNull(result)
        }

        @Test
        @DisplayName("CREATE INDEX 시 중복 인덱스 생성 방지")
        fun `prevents duplicate index creation`() {
            // Given
            val indexName = "idx2"
            val tableName = "users"
            val columns = listOf("name", "email")
            val existingIndex = IndexMetadata(
                "users_idx1",
                "idx1",
                tableName,
                columns,
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByIndexName(indexName)).thenReturn(null)
            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(listOf(existingIndex))

            // When & Then
            val exception = assertThrows(IllegalStateException::class.java) {
                indexMetadataService.createIndex(indexName, tableName, columns)
            }

            assertTrue(exception.message!!.contains("Index with same columns already exists"))
        }
    }

    @Nested
    @DisplayName("인덱스 스캔 결정 테스트 (Selectivity)")
    inner class ShouldUseIndexScanTest {

        @Test
        @DisplayName("낮은 selectivity - 인덱스 사용")
        fun `uses index for low selectivity`() {
            // Given
            val tableName = "users"
            val columnName = "email"
            val selectivity = 0.001  // 0.1%
            val index = IndexMetadata(
                "users_idx_email",
                "idx_email",
                tableName,
                listOf("email"),
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(listOf(index))

            // When
            val decision = indexMetadataService.shouldUseIndexScan(tableName, columnName, selectivity)

            // Then
            assertTrue(decision.useIndex)
            assertEquals(ScanType.INDEX_SCAN, decision.scanType)
            assertEquals("idx_email", decision.selectedIndex?.indexName)
        }

        @Test
        @DisplayName("높은 selectivity - Full table scan 사용")
        fun `uses table scan for high selectivity`() {
            // Given
            val tableName = "users"
            val columnName = "gender"
            val selectivity = 0.5  // 50%
            val index = IndexMetadata(
                "users_idx_gender",
                "idx_gender",
                tableName,
                listOf("gender"),
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(listOf(index))

            // When
            val decision = indexMetadataService.shouldUseIndexScan(tableName, columnName, selectivity)

            // Then
            assertFalse(decision.useIndex)
            assertEquals(ScanType.TABLE_SCAN, decision.scanType)
            assertTrue(decision.reason.contains("High selectivity"))
        }

        @Test
        @DisplayName("인덱스 없음 - Table scan 사용")
        fun `uses table scan when no index`() {
            // Given
            val tableName = "users"
            val columnName = "age"
            val selectivity = 0.01

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(emptyList())

            // When
            val decision = indexMetadataService.shouldUseIndexScan(tableName, columnName, selectivity)

            // Then
            assertFalse(decision.useIndex)
            assertEquals(ScanType.TABLE_SCAN, decision.scanType)
            assertTrue(decision.reason.contains("No index available"))
        }

        @Test
        @DisplayName("복합 인덱스의 두 번째 컬럼 - Table scan 사용")
        fun `uses table scan for non-leading column`() {
            // Given
            val tableName = "users"
            val columnName = "email"
            val selectivity = 0.01
            val compositeIndex = IndexMetadata(
                "users_idx_composite",
                "idx_composite",
                tableName,
                listOf("name", "email"),  // email이 두 번째
                status = IndexStatus.ACTIVE
            )

            `when`(indexMetadataRepository.findByTableNameAndIndexColumnsContaining(tableName, columnName))
                .thenReturn(listOf(compositeIndex))

            // When
            val decision = indexMetadataService.shouldUseIndexScan(tableName, columnName, selectivity)

            // Then
            assertFalse(decision.useIndex)
            assertEquals(ScanType.TABLE_SCAN, decision.scanType)
            assertTrue(decision.reason.contains("not the leading column"))
        }
    }

    @Nested
    @DisplayName("선행 컬럼 확인 테스트")
    inner class IsLeadingColumnTest {

        @Test
        @DisplayName("단일 컬럼 인덱스의 경우 선행 컬럼")
        fun `returns true for single column index`() {
            // Given
            val tableName = "users"
            val columnName = "name"
            val indexes = listOf(
                IndexMetadata(
                    "users_idx_name",
                    "idx_name",
                    tableName,
                    listOf("name"),
                    status = IndexStatus.ACTIVE
                )
            )

            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(indexes)

            // When
            val result = indexMetadataService.isLeadingColumn(tableName, columnName)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("복합 인덱스의 첫 번째 컬럼은 선행 컬럼")
        fun `returns true for leading column in composite index`() {
            // Given
            val tableName = "users"
            val columnName = "name"
            val indexes = listOf(
                IndexMetadata(
                    "users_idx_composite",
                    "idx_composite",
                    tableName,
                    listOf("name", "email"),
                    status = IndexStatus.ACTIVE
                )
            )

            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(indexes)

            // When
            val result = indexMetadataService.isLeadingColumn(tableName, columnName)

            // Then
            assertTrue(result)
        }

        @Test
        @DisplayName("복합 인덱스의 두 번째 컬럼은 선행 컬럼 아님")
        fun `returns false for non-leading column in composite index`() {
            // Given
            val tableName = "users"
            val columnName = "email"
            val indexes = listOf(
                IndexMetadata(
                    "users_idx_composite",
                    "idx_composite",
                    tableName,
                    listOf("name", "email"),
                    status = IndexStatus.ACTIVE
                )
            )

            `when`(indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE))
                .thenReturn(indexes)

            // When
            val result = indexMetadataService.isLeadingColumn(tableName, columnName)

            // Then
            assertFalse(result)
        }
    }
}
