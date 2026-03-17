package study.db.server.db_engine.dto

data class OrderByColumn(
    val columnName: String,
    val ascending: Boolean = true   // true=ASC, false=DESC
)
