package study.db.server.vacuum

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import study.db.common.VacuumStats
import study.db.server.storage.TableFileManager
import java.io.File
import kotlin.math.min
import kotlin.math.pow

/**
 * VACUUM 핵심 로직 서비스
 *
 * VACUUM 작업의 오케스트레이션, 재시도 로직, 임계값 체크 등을 담당합니다.
 */
@Service
class VacuumService(
    private val tableFileManager: TableFileManager,
    private val vacuumLockManager: VacuumLockManager,
    private val vacuumConfig: VacuumConfig
) {
    private val logger = LoggerFactory.getLogger(VacuumService::class.java)

    /**
     * VACUUM 실행 여부 판단
     *
     * @param tableName 테이블 이름
     * @return VACUUM 필요 여부
     */
    fun shouldVacuum(tableName: String): Boolean {
        if (!vacuumConfig.enabled) {
            return false
        }

        val stats = tableFileManager.getTableStatistics(tableName) ?: return false

        // 삭제된 행이 최소 개수 이상이고, 삭제 비율이 임계값 이상인 경우
        return stats.deletedRows >= vacuumConfig.minDeletedRows &&
               stats.deletedRatio >= vacuumConfig.thresholdRatio
    }

    /**
     * 삭제된 행의 비율 계산
     *
     * @param tableName 테이블 이름
     * @return 삭제 비율 (0.0 ~ 1.0)
     */
    fun calculateDeletedRatio(tableName: String): Double {
        val stats = tableFileManager.getTableStatistics(tableName) ?: return 0.0
        return stats.deletedRatio
    }

    /**
     * 디스크 여유 공간 확인
     *
     * VACUUM은 임시로 2배의 디스크 공간이 필요하므로 충분한 공간이 있는지 확인합니다.
     *
     * @return 충분한 여유 공간 여부
     */
    fun hasSufficientDiskSpace(tableName: String): Boolean {
        if (!vacuumConfig.diskSpaceCheckEnabled) {
            return true
        }

        try {
            val stats = tableFileManager.getTableStatistics(tableName) ?: return false
            val tableSize = stats.fileSizeBytes

            // 데이터 디렉토리의 여유 공간 확인
            val dataDirectory = File(tableFileManager.javaClass
                .getDeclaredField("dataDirectory")
                .apply { isAccessible = true }
                .get(tableFileManager) as File, "")

            val freeSpace = dataDirectory.usableSpace
            val requiredSpace = (tableSize * (1 + vacuumConfig.requiredFreeSpaceRatio)).toLong()

            return freeSpace >= requiredSpace
        } catch (e: Exception) {
            logger.warn("Failed to check disk space, proceeding anyway", e)
            return true
        }
    }

    /**
     * VACUUM 실행 (재시도 로직 포함)
     *
     * Copy-on-Write 방식으로 VACUUM을 실행하며, 동시 쓰기 감지 시 재시도합니다.
     *
     * @param tableName 테이블 이름
     * @return VacuumStats 통계 객체
     */
    fun vacuumTable(tableName: String): VacuumStats {
        if (!vacuumConfig.enabled) {
            return VacuumStats.failure("VACUUM is disabled in configuration")
        }

        // 디스크 공간 확인
        if (!hasSufficientDiskSpace(tableName)) {
            return VacuumStats.failure("Insufficient disk space for VACUUM")
        }

        var lastResult: VacuumStats? = null
        var retryCount = 0

        // 재시도 루프
        while (retryCount <= vacuumConfig.maxRetries) {
            val result = vacuumTableOnce(tableName)

            if (result.success) {
                logger.info("VACUUM completed successfully for table '{}' (retries: {})", tableName, retryCount)
                return result.copy(retryCount = retryCount)
            }

            // 쓰기 감지로 인한 abort가 아니면 재시도하지 않음
            if (!result.abortedDueToWrite) {
                logger.error("VACUUM failed for table '{}': {}", tableName, result.errorMessage)
                return result
            }

            lastResult = result
            retryCount++

            if (retryCount <= vacuumConfig.maxRetries) {
                val delayMs = calculateBackoffDelay(retryCount)
                logger.warn(
                    "VACUUM aborted due to concurrent write for table '{}', retrying in {}ms (attempt {}/{})",
                    tableName, delayMs, retryCount, vacuumConfig.maxRetries
                )
                Thread.sleep(delayMs)
            }
        }

        // 최대 재시도 횟수 초과
        logger.error("VACUUM failed for table '{}' after {} retries", tableName, vacuumConfig.maxRetries)
        return lastResult?.copy(
            errorMessage = "VACUUM failed after ${vacuumConfig.maxRetries} retries due to concurrent writes",
            retryCount = retryCount - 1
        ) ?: VacuumStats.failure("VACUUM failed with unknown error")
    }

    /**
     * VACUUM 단일 시도
     *
     * @param tableName 테이블 이름
     * @return VacuumStats 통계 객체
     */
    private fun vacuumTableOnce(tableName: String): VacuumStats {
        try {
            // Write lock 획득 시도 (짧은 타임아웃)
            if (!vacuumLockManager.acquireWriteLock(tableName, timeoutMs = 5000)) {
                return VacuumStats.failure("Failed to acquire write lock for VACUUM")
            }

            try {
                // TableFileManager의 compactTable 호출
                // compactTable은 내부적으로 Copy-on-Write 패턴을 구현
                return tableFileManager.compactTable(tableName)
            } finally {
                vacuumLockManager.releaseWriteLock(tableName)
            }

        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return VacuumStats.failure("VACUUM interrupted: ${e.message}")
        } catch (e: Exception) {
            logger.error("VACUUM failed for table: $tableName", e)
            return VacuumStats.failure("VACUUM error: ${e.message}")
        }
    }

    /**
     * Exponential backoff 지연 시간 계산
     *
     * @param retryCount 현재 재시도 횟수
     * @return 지연 시간 (밀리초)
     */
    private fun calculateBackoffDelay(retryCount: Int): Long {
        val exponentialDelay = (vacuumConfig.retryInitialDelayMs * 2.0.pow(retryCount - 1)).toLong()
        return min(exponentialDelay, vacuumConfig.retryMaxDelayMs)
    }

    /**
     * 테이블이 존재하는지 확인
     *
     * @param tableName 테이블 이름
     * @return 존재 여부
     */
    fun tableExists(tableName: String): Boolean {
        return tableFileManager.getTableStatistics(tableName) != null
    }
}
