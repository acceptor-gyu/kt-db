package study.db.server.vacuum

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import study.db.server.storage.TableFileManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VACUUM 백그라운드 스케줄러
 *
 * 주기적으로 모든 테이블을 스캔하여 VACUUM이 필요한 테이블을 자동으로 정리합니다.
 */
@Component
class VacuumScheduler(
    private val vacuumService: VacuumService,
    private val tableFileManager: TableFileManager,
    private val vacuumConfig: VacuumConfig
) {
    private val logger = LoggerFactory.getLogger(VacuumScheduler::class.java)

    private var scheduler: ScheduledExecutorService? = null
    private val isRunning = AtomicBoolean(false)

    /**
     * 스케줄러 시작
     */
    @Synchronized
    fun start() {
        if (!vacuumConfig.enabled) {
            logger.info("VACUUM scheduler is disabled by configuration")
            return
        }

        if (isRunning.get()) {
            logger.warn("VACUUM scheduler is already running")
            return
        }

        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "vacuum-scheduler").apply {
                isDaemon = true
            }
        }

        val intervalSeconds = vacuumConfig.scanIntervalSeconds
        scheduler?.scheduleAtFixedRate(
            {
                try {
                    scanAndVacuum()
                } catch (e: Exception) {
                    logger.error("Unhandled exception in VACUUM scheduler", e)
                }
            },
            intervalSeconds,  // Initial delay
            intervalSeconds,  // Period
            TimeUnit.SECONDS
        )

        isRunning.set(true)
        logger.info("VACUUM scheduler started (scan interval: {}s, threshold: {}%)",
            intervalSeconds, (vacuumConfig.thresholdRatio * 100).toInt())
    }

    /**
     * 스케줄러 중지
     */
    @Synchronized
    fun stop() {
        if (!isRunning.get()) {
            logger.debug("VACUUM scheduler is not running")
            return
        }

        scheduler?.shutdown()
        try {
            if (scheduler?.awaitTermination(10, TimeUnit.SECONDS) == false) {
                scheduler?.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler?.shutdownNow()
            Thread.currentThread().interrupt()
        }

        scheduler = null
        isRunning.set(false)
        logger.info("VACUUM scheduler stopped")
    }

    /**
     * 주기적 스캔 로직
     *
     * 모든 테이블을 스캔하여 VACUUM이 필요한 테이블을 찾아 실행합니다.
     */
    fun scanAndVacuum() {
        if (!vacuumConfig.enabled) {
            return
        }

        logger.debug("Starting periodic VACUUM scan")

        try {
            val allTables = tableFileManager.listAllTables()
            if (allTables.isEmpty()) {
                logger.debug("No tables found for VACUUM scan")
                return
            }

            logger.debug("Scanning {} tables for VACUUM candidates", allTables.size)

            var vacuumedCount = 0
            var skippedCount = 0

            for (tableName in allTables) {
                try {
                    if (vacuumService.shouldVacuum(tableName)) {
                        logger.info("Table '{}' meets VACUUM threshold, starting automatic VACUUM", tableName)

                        val stats = vacuumService.vacuumTable(tableName)

                        if (stats.success) {
                            vacuumedCount++
                            logger.info(
                                "Automatic VACUUM completed for table '{}': {} deleted rows removed, {} disk space saved",
                                tableName, stats.deletedRowsRemoved, formatBytes(stats.diskSpaceSaved)
                            )
                        } else {
                            logger.warn("Automatic VACUUM failed for table '{}': {}", tableName, stats.errorMessage)
                        }
                    } else {
                        skippedCount++
                        logger.trace("Table '{}' does not need VACUUM", tableName)
                    }
                } catch (e: Exception) {
                    logger.error("Error during VACUUM scan for table '{}'", tableName, e)
                }
            }

            logger.debug(
                "VACUUM scan completed: {} tables vacuumed, {} tables skipped",
                vacuumedCount, skippedCount
            )

        } catch (e: Exception) {
            logger.error("Error during VACUUM scan", e)
        }
    }

    /**
     * 수동 스캔 트리거 (테스트용)
     *
     * 스케줄러와 무관하게 즉시 VACUUM 스캔을 실행합니다.
     */
    fun triggerScan() {
        logger.info("Manual VACUUM scan triggered")
        scanAndVacuum()
    }

    /**
     * 스케줄러 실행 상태 확인
     *
     * @return 실행 중 여부
     */
    fun isRunning(): Boolean {
        return isRunning.get()
    }

    /**
     * 바이트 단위를 사람이 읽기 쉬운 형태로 변환
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }
}
