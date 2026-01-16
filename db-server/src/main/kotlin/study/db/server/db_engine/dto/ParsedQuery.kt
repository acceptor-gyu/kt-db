package study.db.server.db_engine.dto

data class ParsedQuery(
    val tableName: String,
    val selectColumns: List<String>,
    val whereConditions: List<WhereCondition>,
    val orderBy: List<String>
)
