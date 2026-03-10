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

    /**
     * WHERE 절에 사용된 모든 컬럼명을 추출
     */
    fun getColumnNames(): Set<String> {
        return when (this) {
            is None -> emptySet()
            is Single -> setOf(condition.columnName)
            is And -> left.getColumnNames() + right.getColumnNames()
            is Or -> left.getColumnNames() + right.getColumnNames()
        }
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

        /**
         * WHERE 절 문자열을 파싱하여 WhereClause 객체로 변환
         *
         * 지원 형식:
         * - 단순 등호: name='Alice', id=1
         * - 비교 연산자: age > 20, age >= 30, age < 10, age <= 5, age != 0
         * - AND 조건: age > 20 AND status='active'
         * - OR 조건: id=1 OR id=2
         * - 혼합: (AND가 OR보다 우선순위 높음)
         *
         * @param whereString WHERE 절 문자열 (null이면 None 반환)
         * @return WhereClause 객체
         * @throws IllegalArgumentException 잘못된 WHERE 구문
         */
        fun parse(whereString: String?): WhereClause {
            if (whereString.isNullOrBlank()) return None

            val trimmed = whereString.trim()

            // OR로 분할 (OR이 가장 낮은 우선순위)
            val orParts = splitByKeyword(trimmed, "OR")
            if (orParts.size > 1) {
                return orParts.map { parse(it) }
                    .reduce { left, right -> Or(left, right) }
            }

            // AND로 분할
            val andParts = splitByKeyword(trimmed, "AND")
            if (andParts.size > 1) {
                return andParts.map { parse(it) }
                    .reduce { left, right -> And(left, right) }
            }

            // 단일 조건 파싱
            return Single(parseCondition(trimmed))
        }

        /**
         * 키워드(AND, OR)로 문자열을 분할
         * 키워드 앞뒤에 공백이 있어야 함 (컬럼명에 포함된 경우 방지)
         */
        private fun splitByKeyword(input: String, keyword: String): List<String> {
            val pattern = Regex("""\s+$keyword\s+""", RegexOption.IGNORE_CASE)
            return input.split(pattern).map { it.trim() }
        }

        /**
         * 단일 조건 문자열을 Condition 객체로 파싱
         *
         * 지원 형식: column OP value
         * OP: =, !=, >=, <=, >, <
         * value: 'string', "string", number
         */
        private fun parseCondition(conditionStr: String): Condition {
            // 연산자 매칭 (>=, <=, !=를 먼저 시도 - 순서 중요)
            val regex = Regex("""(\w+)\s*(>=|<=|!=|>|<|=)\s*(?:'([^']*)'|"([^"]*)"|(\S+))""")
            val match = regex.find(conditionStr.trim())
                ?: throw IllegalArgumentException("Invalid WHERE condition: $conditionStr")

            val columnName = match.groupValues[1]
            val operator = Condition.Operator.fromString(match.groupValues[2])
            val value = match.groupValues[3].ifEmpty {
                match.groupValues[4].ifEmpty {
                    match.groupValues[5]
                }
            }

            return Condition(columnName, operator, value)
        }
    }
}
