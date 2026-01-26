package study.db.common.where

/**
 * Condition - WHERE 절의 개별 조건을 표현
 *
 * 예: name = 'Alice', age > 20
 *
 * @property columnName 비교 대상 컬럼명
 * @property operator 비교 연산자
 * @property value 비교 값
 */
data class Condition(
    val columnName: String,
    val operator: Operator,
    val value: String
) {
    enum class Operator {
        EQUAL,          // =
        NOT_EQUAL,      // !=
        GREATER_THAN,   // >
        LESS_THAN,      // <
        GREATER_OR_EQUAL, // >=
        LESS_OR_EQUAL,  // <=
        LIKE;           // LIKE (나중에 구현)

        companion object {
            fun fromString(op: String): Operator = when (op.trim()) {
                "=" -> EQUAL
                "!=" -> NOT_EQUAL
                ">" -> GREATER_THAN
                "<" -> LESS_THAN
                ">=" -> GREATER_OR_EQUAL
                "<=" -> LESS_OR_EQUAL
                "LIKE" -> LIKE
                else -> throw IllegalArgumentException("Unsupported operator: $op")
            }
        }
    }

    override fun toString(): String = "$columnName $operator $value"
}
