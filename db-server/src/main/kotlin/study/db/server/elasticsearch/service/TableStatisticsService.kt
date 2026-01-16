package study.db.server.elasticsearch.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import study.db.server.elasticsearch.document.ColumnStatistics
import study.db.server.elasticsearch.document.TableStatistics
import study.db.server.elasticsearch.repository.TableStatisticsRepository
import java.time.Instant

@Service
class TableStatisticsService(
    private val tableStatisticsRepository: TableStatisticsRepository
) {
    private val logger = LoggerFactory.getLogger(TableStatisticsService::class.java)

    /**
     * 테이블 통계 초기화 (CREATE TABLE 시 호출)
     */
    fun initializeStatistics(tableName: String, columns: List<String>): TableStatistics {
        return try {
            val columnStats = columns.map { columnName ->
                ColumnStatistics(
                    columnName = columnName,
                    distinctCount = 0,
                    nullCount = 0
                )
            }

            val statistics = TableStatistics(
                statsId = tableName,
                tableName = tableName,
                totalRows = 0,
                columnStats = columnStats,
                updatedAt = Instant.now()
            )

            val saved = tableStatisticsRepository.save(statistics)
            logger.info("Initialized statistics for table: $tableName")
            saved
        } catch (e: Exception) {
            logger.error("Failed to initialize statistics for table: $tableName", e)
            throw e
        }
    }

    /**
     * 총 행 수 업데이트 (INSERT/DELETE 시 호출)
     */
    fun updateTotalRows(tableName: String, delta: Long): TableStatistics? {
        return try {
            val stats = tableStatisticsRepository.findByTableName(tableName)
                ?: run {
                    logger.warn("Statistics not found for table: $tableName")
                    return null
                }

            val updatedStats = stats.copy(
                totalRows = (stats.totalRows + delta).coerceAtLeast(0),
                updatedAt = Instant.now()
            )

            val saved = tableStatisticsRepository.save(updatedStats)
            logger.debug("Updated total rows for $tableName: delta=$delta, new total=${saved.totalRows}")
            saved
        } catch (e: Exception) {
            logger.error("Failed to update total rows for table: $tableName", e)
            null
        }
    }

    /**
     * 컬럼 통계 업데이트
     *
     * INSERT 시 호출되며, 각 컬럼의 고유값 개수를 업데이트합니다.
     * 실제 구현에서는 실제 데이터를 분석해야 하지만,
     * 여기서는 예시로 간단하게 구현합니다.
     */
    fun updateColumnStatistics(
        tableName: String,
        columnName: String,
        distinctCountDelta: Long = 1,
        nullCountDelta: Long = 0
    ): TableStatistics? {
        return try {
            val stats = tableStatisticsRepository.findByTableName(tableName)
                ?: run {
                    logger.warn("Statistics not found for table: $tableName")
                    return null
                }

            val updatedColumnStats = stats.columnStats.map { colStat ->
                if (colStat.columnName == columnName) {
                    colStat.copy(
                        distinctCount = (colStat.distinctCount + distinctCountDelta).coerceAtLeast(0),
                        nullCount = (colStat.nullCount + nullCountDelta).coerceAtLeast(0)
                    )
                } else {
                    colStat
                }
            }

            val updatedStats = stats.copy(
                columnStats = updatedColumnStats,
                updatedAt = Instant.now()
            )

            val saved = tableStatisticsRepository.save(updatedStats)
            logger.debug("Updated column statistics for $tableName.$columnName")
            saved
        } catch (e: Exception) {
            logger.error("Failed to update column statistics for $tableName.$columnName", e)
            null
        }
    }

    /**
     * 전체 통계 업데이트 (INSERT 시 호출)
     *
     * 실제 데이터를 분석하여 통계를 업데이트합니다.
     * 여기서는 간단한 예시 구현입니다.
     */
    fun updateStatisticsOnInsert(
        tableName: String,
        insertedData: Map<String, String>
    ): TableStatistics? {
        return try {
            val stats = tableStatisticsRepository.findByTableName(tableName)
                ?: run {
                    logger.warn("Statistics not found for table: $tableName")
                    return null
                }

            // 1. 총 행 수 증가
            val newTotalRows = stats.totalRows + 1

            // 2. 각 컬럼 통계 업데이트
            val updatedColumnStats = stats.columnStats.map { colStat ->
                val columnValue = insertedData[colStat.columnName]

                if (columnValue == null) {
                    // NULL 값
                    colStat.copy(nullCount = colStat.nullCount + 1)
                } else {
                    // 실제로는 중복 체크를 해야 하지만, 간단히 distinctCount를 증가
                    // 실제 구현에서는 HyperLogLog 같은 알고리즘 사용
                    colStat.copy(
                        distinctCount = colStat.distinctCount + 1,
                        avgLength = if (colStat.avgLength != null) {
                            (colStat.avgLength * (newTotalRows - 1) + columnValue.length) / newTotalRows
                        } else {
                            columnValue.length.toDouble()
                        }
                    )
                }
            }

            val updatedStats = stats.copy(
                totalRows = newTotalRows,
                columnStats = updatedColumnStats,
                updatedAt = Instant.now()
            )

            val saved = tableStatisticsRepository.save(updatedStats)
            logger.debug("Updated statistics on insert for table: $tableName")
            saved
        } catch (e: Exception) {
            logger.error("Failed to update statistics on insert for table: $tableName", e)
            null
        }
    }

    /**
     * 테이블 통계 조회
     */
    fun getStatistics(tableName: String): TableStatistics? {
        return try {
            tableStatisticsRepository.findByTableName(tableName)
        } catch (e: Exception) {
            logger.error("Failed to get statistics for table: $tableName", e)
            null
        }
    }

    /**
     * 특정 컬럼의 통계 조회
     */
    fun getColumnStatistics(tableName: String, columnName: String): ColumnStatistics? {
        return try {
            val stats = tableStatisticsRepository.findByTableName(tableName)
            stats?.columnStats?.find { it.columnName == columnName }
        } catch (e: Exception) {
            logger.error("Failed to get column statistics for $tableName.$columnName", e)
            null
        }
    }

    /**
     * Selectivity 계산 (EXPLAIN에서 사용!)
     *
     * WHERE column = 'value' 형태의 조건에 대한 selectivity 계산
     *
     * Selectivity = 1 / distinctCount
     * - distinctCount가 높을수록 selectivity가 낮음 → 인덱스 효율적
     * - distinctCount가 낮을수록 selectivity가 높음 → Full scan이 나을 수 있음
     *
     * 예:
     * - email (distinctCount=1000) → selectivity = 0.001 (0.1%) → INDEX_SCAN
     * - gender (distinctCount=2) → selectivity = 0.5 (50%) → TABLE_SCAN
     */
    fun calculateSelectivity(tableName: String, columnName: String): Double {
        return try {
            val colStats = getColumnStatistics(tableName, columnName)
            colStats?.calculateSelectivity() ?: 1.0
        } catch (e: Exception) {
            logger.error("Failed to calculate selectivity for $tableName.$columnName", e)
            1.0  // 최악의 경우로 가정
        }
    }

    /**
     * 모든 테이블 통계 조회
     */
    fun getAllStatistics(): List<TableStatistics> {
        return try {
            tableStatisticsRepository.findAllByOrderByUpdatedAtDesc()
        } catch (e: Exception) {
            logger.error("Failed to get all statistics", e)
            emptyList()
        }
    }

    /**
     * 테이블 통계 삭제 (DROP TABLE 시 호출)
     */
    fun deleteStatistics(tableName: String): Boolean {
        return try {
            val stats = tableStatisticsRepository.findByTableName(tableName)
            if (stats != null) {
                tableStatisticsRepository.delete(stats)
                logger.info("Deleted statistics for table: $tableName")
                true
            } else {
                logger.warn("Statistics not found for table: $tableName")
                false
            }
        } catch (e: Exception) {
            logger.error("Failed to delete statistics for table: $tableName", e)
            false
        }
    }
}
