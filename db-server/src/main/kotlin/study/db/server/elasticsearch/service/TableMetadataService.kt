package study.db.server.elasticsearch.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import study.db.server.elasticsearch.document.ColumnInfo
import study.db.server.elasticsearch.document.TableMetadata
import study.db.server.elasticsearch.document.TableStatus
import study.db.server.elasticsearch.repository.TableMetadataRepository
import java.time.Instant

@Service
class TableMetadataService(
    private val tableMetadataRepository: TableMetadataRepository
) {
    private val logger = LoggerFactory.getLogger(TableMetadataService::class.java)

    /**
     * CREATE TABLE 명령으로부터 테이블 메타데이터 생성
     */
    fun createTable(
        tableName: String,
        columns: List<ColumnInfo>
    ): TableMetadata {
        return try {
            // 기존 테이블이 있는지 확인 (DROPPED 상태일 수도 있음)
            val existingTable = tableMetadataRepository.findByTableName(tableName)
            if (existingTable != null && existingTable.status == TableStatus.ACTIVE) {
                logger.warn("Table already exists: $tableName")
                throw IllegalStateException("Table already exists: $tableName")
            }

            val tableMetadata = TableMetadata(
                tableId = tableName,
                tableName = tableName,
                columns = columns,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                status = TableStatus.ACTIVE,
                estimatedRowCount = 0
            )

            val saved = tableMetadataRepository.save(tableMetadata)
            logger.info("Created table metadata: $tableName with ${columns.size} columns")
            saved
        } catch (e: Exception) {
            logger.error("Failed to create table metadata: $tableName", e)
            throw e
        }
    }

    /**
     * 테이블의 예상 행 수 업데이트 (INSERT/DELETE 시 호출)
     */
    fun updateRowCount(tableName: String, delta: Long): TableMetadata? {
        return try {
            val table = tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE)
                ?: run {
                    logger.warn("Active table not found: $tableName")
                    return null
                }

            val updatedTable = table.copy(
                estimatedRowCount = (table.estimatedRowCount + delta).coerceAtLeast(0),
                updatedAt = Instant.now()
            )

            val saved = tableMetadataRepository.save(updatedTable)
            logger.debug("Updated row count for table $tableName: delta=$delta, new count=${saved.estimatedRowCount}")
            saved
        } catch (e: Exception) {
            logger.error("Failed to update row count for table: $tableName", e)
            null
        }
    }

    /**
     * DROP TABLE 명령으로부터 테이블 상태를 DROPPED로 변경
     */
    fun dropTable(tableName: String): TableMetadata? {
        return try {
            val table = tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE)
                ?: run {
                    logger.warn("Active table not found: $tableName")
                    return null
                }

            val droppedTable = table.copy(
                status = TableStatus.DROPPED,
                updatedAt = Instant.now()
            )

            val saved = tableMetadataRepository.save(droppedTable)
            logger.info("Dropped table: $tableName")
            saved
        } catch (e: Exception) {
            logger.error("Failed to drop table: $tableName", e)
            null
        }
    }

    /**
     * 테이블 메타데이터 조회
     */
    fun getTableMetadata(tableName: String): TableMetadata? {
        return try {
            tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE)
        } catch (e: Exception) {
            logger.error("Failed to get table metadata: $tableName", e)
            null
        }
    }

    /**
     * 모든 활성 테이블 조회
     */
    fun getAllActiveTables(): List<TableMetadata> {
        return try {
            tableMetadataRepository.findByStatus(TableStatus.ACTIVE)
        } catch (e: Exception) {
            logger.error("Failed to get all active tables", e)
            emptyList()
        }
    }

    /**
     * 테이블 존재 여부 확인
     */
    fun tableExists(tableName: String): Boolean {
        return try {
            tableMetadataRepository.findByTableNameAndStatus(tableName, TableStatus.ACTIVE) != null
        } catch (e: Exception) {
            logger.error("Failed to check table existence: $tableName", e)
            false
        }
    }

    /**
     * 테이블의 컬럼 정보 조회
     */
    fun getTableColumns(tableName: String): List<ColumnInfo>? {
        return getTableMetadata(tableName)?.columns
    }
}
