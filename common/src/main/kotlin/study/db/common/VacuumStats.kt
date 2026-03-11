package study.db.common

import java.time.Instant

/**
 * VACUUM 실행 통계 데이터 클래스
 *
 * VACUUM 작업의 결과를 추적하고 보고하기 위한 통계 정보를 담고 있습니다.
 */
data class VacuumStats(
    // 행 통계
    val totalRowsBefore: Int,
    val activeRows: Int,
    val deletedRowsRemoved: Int,

    // 디스크 통계
    val diskSpaceBefore: Long,  // bytes
    val diskSpaceAfter: Long,   // bytes
    val diskSpaceSaved: Long,   // bytes
    val reductionPercent: Double,

    // 페이지 통계
    val pagesBefore: Int,
    val pagesAfter: Int,
    val pagesFreed: Int,

    // 실행 정보
    val startTime: Instant,
    val endTime: Instant,
    val durationMs: Long,

    // 상태
    val success: Boolean,
    val errorMessage: String? = null,
    val abortedDueToWrite: Boolean = false,
    val retryCount: Int = 0
) {
    companion object {
        /**
         * 실패한 VACUUM 통계 생성
         */
        fun failure(
            errorMessage: String,
            abortedDueToWrite: Boolean = false,
            retryCount: Int = 0
        ): VacuumStats {
            val now = Instant.now()
            return VacuumStats(
                totalRowsBefore = 0,
                activeRows = 0,
                deletedRowsRemoved = 0,
                diskSpaceBefore = 0,
                diskSpaceAfter = 0,
                diskSpaceSaved = 0,
                reductionPercent = 0.0,
                pagesBefore = 0,
                pagesAfter = 0,
                pagesFreed = 0,
                startTime = now,
                endTime = now,
                durationMs = 0,
                success = false,
                errorMessage = errorMessage,
                abortedDueToWrite = abortedDueToWrite,
                retryCount = retryCount
            )
        }

        /**
         * VACUUM이 불필요한 경우 (삭제된 행이 없음)
         */
        fun notNeeded(): VacuumStats {
            val now = Instant.now()
            return VacuumStats(
                totalRowsBefore = 0,
                activeRows = 0,
                deletedRowsRemoved = 0,
                diskSpaceBefore = 0,
                diskSpaceAfter = 0,
                diskSpaceSaved = 0,
                reductionPercent = 0.0,
                pagesBefore = 0,
                pagesAfter = 0,
                pagesFreed = 0,
                startTime = now,
                endTime = now,
                durationMs = 0,
                success = true,
                errorMessage = "No deleted rows to remove"
            )
        }
    }

    /**
     * 사람이 읽기 쉬운 형태로 통계 출력
     */
    fun toSummary(): String {
        if (!success) {
            return "VACUUM failed: $errorMessage${if (abortedDueToWrite) " (aborted due to concurrent write)" else ""}"
        }

        return buildString {
            appendLine("VACUUM completed successfully:")
            appendLine("- Deleted rows removed: $deletedRowsRemoved")
            appendLine("- Disk space saved: ${formatBytes(diskSpaceSaved)} (${String.format("%.1f", reductionPercent)}%)")
            appendLine("- Pages freed: $pagesFreed (${pagesBefore} → ${pagesAfter})")
            appendLine("- Duration: ${durationMs}ms")
            if (retryCount > 0) {
                appendLine("- Retries: $retryCount")
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
            else -> "${bytes / (1024 * 1024 * 1024)}GB"
        }
    }
}
