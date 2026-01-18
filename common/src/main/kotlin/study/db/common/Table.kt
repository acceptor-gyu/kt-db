package study.db.common

import kotlinx.serialization.Serializable

@Serializable
data class Table(
    val tableName: String,
    val dataType: Map<String, String>,
    val rows: List<Map<String, String>> = emptyList(),
) {
    // Backward compatibility property
    @Deprecated("Use rows instead", ReplaceWith("rows.lastOrNull() ?: emptyMap()"))
    val value: Map<String, String>
        get() = rows.lastOrNull() ?: emptyMap()
}
