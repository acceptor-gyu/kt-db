package study.db.server.elasticsearch.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import study.db.server.elasticsearch.document.IndexMetadata
import study.db.server.elasticsearch.document.IndexStatus
import study.db.server.elasticsearch.document.IndexType
import study.db.server.elasticsearch.repository.IndexMetadataRepository
import java.time.Instant

@Service
class IndexMetadataService(
    private val indexMetadataRepository: IndexMetadataRepository
) {
    private val logger = LoggerFactory.getLogger(IndexMetadataService::class.java)

    /**
     * CREATE INDEX 명령으로부터 인덱스 메타데이터 생성
     */
    fun createIndex(
        indexName: String,
        tableName: String,
        columns: List<String>,
        indexType: IndexType = IndexType.BTREE,
        unique: Boolean = false
    ): IndexMetadata {
        return try {
            // 1. 같은 이름의 인덱스가 있는지 확인
            val existingIndex = indexMetadataRepository.findByIndexName(indexName)
            if (existingIndex != null && existingIndex.status == IndexStatus.ACTIVE) {
                logger.warn("Index already exists: $indexName")
                throw IllegalStateException("Index already exists: $indexName")
            }

            // 2. 같은 컬럼 구성(순서 포함)의 인덱스가 있는지 확인
            val duplicateIndex = findDuplicateIndex(tableName, columns)
            if (duplicateIndex != null) {
                logger.warn("Duplicate index found: ${duplicateIndex.indexName} has same columns $columns")
                throw IllegalStateException(
                    "Index with same columns already exists: ${duplicateIndex.indexName} on $columns"
                )
            }

            val indexId = "${tableName}_${indexName}"
            val indexMetadata = IndexMetadata(
                indexId = indexId,
                indexName = indexName,
                tableName = tableName,
                indexColumns = columns,
                indexType = indexType,
                unique = unique,
                createdAt = Instant.now(),
                status = IndexStatus.ACTIVE
            )

            val saved = indexMetadataRepository.save(indexMetadata)
            logger.info("Created index metadata: $indexName on table $tableName with columns $columns")
            saved
        } catch (e: Exception) {
            logger.error("Failed to create index metadata: $indexName", e)
            throw e
        }
    }

    /**
     * DROP INDEX 명령으로부터 인덱스 상태를 DROPPED로 변경
     */
    fun dropIndex(indexName: String): IndexMetadata? {
        return try {
            val index = indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE)
                ?: run {
                    logger.warn("Active index not found: $indexName")
                    return null
                }

            val droppedIndex = index.copy(
                status = IndexStatus.DROPPED
            )

            val saved = indexMetadataRepository.save(droppedIndex)
            logger.info("Dropped index: $indexName")
            saved
        } catch (e: Exception) {
            logger.error("Failed to drop index: $indexName", e)
            null
        }
    }

    /**
     * 인덱스 메타데이터 조회
     */
    fun getIndexMetadata(indexName: String): IndexMetadata? {
        return try {
            indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE)
        } catch (e: Exception) {
            logger.error("Failed to get index metadata: $indexName", e)
            null
        }
    }

    /**
     * 특정 테이블의 모든 활성 인덱스 조회
     */
    fun getIndexesForTable(tableName: String): List<IndexMetadata> {
        return try {
            indexMetadataRepository.findByTableNameAndStatus(tableName, IndexStatus.ACTIVE)
        } catch (e: Exception) {
            logger.error("Failed to get indexes for table: $tableName", e)
            emptyList()
        }
    }

    /**
     * 특정 컬럼에 인덱스가 있는지 조회 (EXPLAIN에서 가장 중요!)
     *
     * EXPLAIN SELECT * FROM users WHERE name = 'Alice'
     * → getIndexesForColumn("users", "name")
     * → 인덱스 있으면 INDEX_SCAN, 없으면 TABLE_SCAN
     */
    fun getIndexesForColumn(tableName: String, columnName: String): List<IndexMetadata> {
        return try {
            val allIndexes = indexMetadataRepository
                .findByTableNameAndIndexColumnsContaining(tableName, columnName)

            // ACTIVE 상태인 인덱스만 필터링
            allIndexes.filter { it.status == IndexStatus.ACTIVE }
        } catch (e: Exception) {
            logger.error("Failed to get indexes for column: $tableName.$columnName", e)
            emptyList()
        }
    }

    /**
     * 인덱스 존재 여부 확인
     */
    fun indexExists(indexName: String): Boolean {
        return try {
            indexMetadataRepository.findByIndexNameAndStatus(indexName, IndexStatus.ACTIVE) != null
        } catch (e: Exception) {
            logger.error("Failed to check index existence: $indexName", e)
            false
        }
    }

    /**
     * 모든 활성 인덱스 조회
     */
    fun getAllActiveIndexes(): List<IndexMetadata> {
        return try {
            indexMetadataRepository.findByStatus(IndexStatus.ACTIVE)
        } catch (e: Exception) {
            logger.error("Failed to get all active indexes", e)
            emptyList()
        }
    }

    /**
     * 특정 컬럼이 인덱스의 첫 번째 컬럼인지 확인
     * (복합 인덱스의 경우, 첫 번째 컬럼만 단독으로 사용 가능)
     */
    fun isLeadingColumn(tableName: String, columnName: String): Boolean {
        return try {
            val indexes = getIndexesForTable(tableName)
            indexes.any { it.indexColumns.firstOrNull() == columnName }
        } catch (e: Exception) {
            logger.error("Failed to check if column is leading: $tableName.$columnName", e)
            false
        }
    }

    /**
     * 중복 인덱스 찾기 (같은 컬럼 구성 + 순서)
     *
     * 예:
     * - 기존: CREATE INDEX idx1 ON users(name, email)
     * - 시도: CREATE INDEX idx2 ON users(name, email)  ← 중복!
     * - 다름: CREATE INDEX idx3 ON users(email, name)  ← 순서가 다르므로 중복 아님
     */
    fun findDuplicateIndex(tableName: String, columns: List<String>): IndexMetadata? {
        return try {
            val existingIndexes = getIndexesForTable(tableName)
            existingIndexes.find { it.indexColumns == columns }
        } catch (e: Exception) {
            logger.error("Failed to find duplicate index for $tableName with columns $columns", e)
            null
        }
    }

    /**
     * 인덱스를 사용할지 Full Table Scan을 할지 결정
     *
     * Selectivity (선택도)를 고려:
     * - 조회할 데이터 비율이 낮으면 (< 5-30%) → INDEX_SCAN
     * - 조회할 데이터 비율이 높으면 (>= 5-30%) → TABLE_SCAN (더 빠름)
     *
     * @param tableName 테이블 이름
     * @param columnName 조건절에 사용된 컬럼
     * @param estimatedSelectivity 예상 선택도 (0.0 ~ 1.0)
     *                             - 예: WHERE id = 1 → 0.001 (0.1%)
     *                             - 예: WHERE gender = 'M' → 0.5 (50%)
     * @return IndexScanDecision (사용할지 여부 + 이유)
     */
    fun shouldUseIndexScan(
        tableName: String,
        columnName: String,
        estimatedSelectivity: Double
    ): IndexScanDecision {
        return try {
            // 1. 인덱스가 있는지 확인
            val indexes = getIndexesForColumn(tableName, columnName)
            if (indexes.isEmpty()) {
                return IndexScanDecision(
                    useIndex = false,
                    reason = "No index available on column $columnName",
                    scanType = ScanType.TABLE_SCAN
                )
            }

            // 2. Selectivity 임계값 확인 (일반적으로 5~30% 사용)
            val selectivityThreshold = 0.15  // 15%

            if (estimatedSelectivity > selectivityThreshold) {
                return IndexScanDecision(
                    useIndex = false,
                    reason = "High selectivity (${"%.2f".format(estimatedSelectivity * 100)}%) - Full table scan is faster",
                    scanType = ScanType.TABLE_SCAN,
                    estimatedSelectivity = estimatedSelectivity
                )
            }

            // 3. Leading column인지 확인 (복합 인덱스의 경우)
            val usableIndexes = indexes.filter { it.indexColumns.first() == columnName }
            if (usableIndexes.isEmpty()) {
                return IndexScanDecision(
                    useIndex = false,
                    reason = "Column $columnName is not the leading column in composite index",
                    scanType = ScanType.TABLE_SCAN
                )
            }

            // 4. 인덱스 사용 결정
            IndexScanDecision(
                useIndex = true,
                reason = "Low selectivity (${"%.2f".format(estimatedSelectivity * 100)}%) - Index scan is efficient",
                scanType = ScanType.INDEX_SCAN,
                selectedIndex = usableIndexes.first(),
                estimatedSelectivity = estimatedSelectivity
            )
        } catch (e: Exception) {
            logger.error("Failed to decide index scan for $tableName.$columnName", e)
            IndexScanDecision(
                useIndex = false,
                reason = "Error during decision: ${e.message}",
                scanType = ScanType.TABLE_SCAN
            )
        }
    }
}

/**
 * 인덱스 스캔 사용 여부 결정 결과
 */
data class IndexScanDecision(
    val useIndex: Boolean,
    val reason: String,
    val scanType: ScanType,
    val selectedIndex: IndexMetadata? = null,
    val estimatedSelectivity: Double? = null
)

enum class ScanType {
    TABLE_SCAN,      // Full table scan
    INDEX_SCAN,      // Index scan
    INDEX_SEEK       // Index seek (특정 값 직접 찾기)
}
