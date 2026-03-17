package study.db.server.db_engine.dto

data class ParsedQuery(
    val tableName: String,
    val selectColumns: List<String>,
    val whereConditions: List<WhereCondition>,
    val orderBy: List<String>,             // 기존 유지 (삭제 금지)
    // 신규 필드 (기본값 필수)
    val whereString: String? = null,
    val orderByColumns: List<OrderByColumn> = emptyList(),
    val limit: Int? = null,
    val offset: Int? = null
)
