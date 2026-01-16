package study.db.common.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DbRequest(
    val command: DbCommand,
    val tableName: String? = null,
    val columns: Map<String, String>? = null,
    val values: Map<String, String>? = null,
    val condition: String? = null,
    val sql: String? = null  // For EXPLAIN and other raw SQL commands
)
