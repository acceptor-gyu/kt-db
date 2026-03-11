package study.db.server.vacuum

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * VACUUM 기능 설정
 *
 * application.properties에서 vacuum.* 속성을 읽어옵니다.
 */
@Component
@ConfigurationProperties(prefix = "vacuum")
data class VacuumConfig(
    var enabled: Boolean = true,
    var thresholdRatio: Double = 0.3,  // 30% 삭제된 행
    var minDeletedRows: Int = 100,
    var scanIntervalSeconds: Long = 60,
    var maxRetries: Int = 5,
    var retryInitialDelayMs: Long = 1000,
    var retryMaxDelayMs: Long = 60000,
    var diskSpaceCheckEnabled: Boolean = true,
    var requiredFreeSpaceRatio: Double = 0.5  // 50% 여유 공간 필요
)
