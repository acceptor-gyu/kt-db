package study.db.common.where

/**
 * WhereClause - WHERE 절 전체를 표현하는 클래스
 *
 * 단일 조건 또는 AND/OR로 결합된 여러 조건을 표현합니다.
 *
 * 예:
 * - WHERE name = 'Alice'  →  Single condition
 * - WHERE age > 20 AND city = 'Seoul'  →  AND of two conditions
 */
sealed class WhereClause {
    /**
     * 단일 조건
     */
    data class Single(val condition: Condition) : WhereClause() {
        override fun toString(): String = condition.toString()
    }

    /**
     * AND로 결합된 조건들
     */
    data class And(val left: WhereClause, val right: WhereClause) : WhereClause() {
        override fun toString(): String = "($left AND $right)"
    }

    /**
     * OR로 결합된 조건들
     */
    data class Or(val left: WhereClause, val right: WhereClause) : WhereClause() {
        override fun toString(): String = "($left OR $right)"
    }

    /**
     * WHERE 절 없음 (전체 선택)
     */
    object None : WhereClause() {
        override fun toString(): String = "NONE"
    }

    companion object {
        /**
         * 단일 조건으로 WhereClause 생성
         */
        fun of(columnName: String, operator: Condition.Operator, value: String): WhereClause {
            return Single(Condition(columnName, operator, value))
        }

        /**
         * 조건 없음
         */
        fun none(): WhereClause = None
    }
}
