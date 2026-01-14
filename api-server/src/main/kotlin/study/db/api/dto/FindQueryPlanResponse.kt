package study.db.api.dto

data class FindQueryPlanResponse(
    val id: Int,
    val selectType: String,
    val table: String,
    val type: String, // ALL, index, range, ref, const
    val possibleKeys: List<String>?,
    val key: String?, // 선택된 인덱스
    val keyLen: Int?,
    val rows: Long, // 예상 행 수
    val extra: String? // "Using where", "Using index" 등
)

fun format(result: FindQueryPlanResponse): String {
    return """
    | id | select_type | table | type  | possible_keys | key | rows | Extra |
    |----|-------------|-------|-------|---------------|-----|------|-------|
    | ${result.id} | ${result.selectType} | ${result.table} | ${result.type} | ${result.possibleKeys?.joinToString(",")} | ${result.key} | ${result.rows} | ${result.extra} |
    """.trimIndent()
}
