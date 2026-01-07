package study.db.common

import kotlinx.serialization.Serializable

@Serializable
data class Table(
    val tableName: String,
    val dataType: Map<String, String>,
    val value: Map<String, String> = emptyMap(),
)
