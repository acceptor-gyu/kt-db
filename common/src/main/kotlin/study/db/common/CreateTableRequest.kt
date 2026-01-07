package study.db.common

import kotlinx.serialization.Serializable

@Serializable
data class CreateTableRequest(
    val tableName: String = "",
    val columns: Map<String, String>? = null,
    val values: Map<String, String>? = null
)
