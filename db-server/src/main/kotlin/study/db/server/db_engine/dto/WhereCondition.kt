package study.db.server.db_engine.dto

data class WhereCondition(
    val column: String,
    val operator: String,
    val value: String
)
