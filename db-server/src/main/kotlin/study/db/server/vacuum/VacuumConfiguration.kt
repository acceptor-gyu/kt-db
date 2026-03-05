package study.db.server.vacuum

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * VACUUM 기능 Spring 설정
 *
 * VacuumConfig를 활성화하고 모든 VACUUM 관련 빈을 등록합니다.
 */
@Configuration
@EnableConfigurationProperties(VacuumConfig::class)
class VacuumConfiguration
