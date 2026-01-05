package study.db.crud

data class Table(
    val tableName: String,
    val dataType: Map<String, String>,
    val value: Map<String, Any>,
)
