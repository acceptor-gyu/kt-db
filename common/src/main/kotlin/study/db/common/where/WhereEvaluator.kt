package study.db.common.where

import study.db.common.Row

/**
 * WhereEvaluator - WHERE 조건을 평가하는 유틸리티
 *
 * Row가 주어진 WHERE 조건을 만족하는지 검사합니다.
 */
object WhereEvaluator {

    /**
     * Row가 WHERE 조건을 만족하는지 평가
     *
     * @param row 평가 대상 Row
     * @param whereClause WHERE 조건
     * @param dataType 컬럼 타입 정보 (INT 비교 등을 위해 필요)
     * @return true면 조건 만족, false면 불만족
     */
    fun matches(row: Row, whereClause: WhereClause, dataType: Map<String, String>): Boolean {
        return when (whereClause) {
            is WhereClause.None -> true
            is WhereClause.Single -> evaluateCondition(row, whereClause.condition, dataType)
            is WhereClause.And -> {
                matches(row, whereClause.left, dataType) && matches(row, whereClause.right, dataType)
            }
            is WhereClause.Or -> {
                matches(row, whereClause.left, dataType) || matches(row, whereClause.right, dataType)
            }
        }
    }

    /**
     * 개별 조건 평가
     */
    private fun evaluateCondition(
        row: Row,
        condition: Condition,
        dataType: Map<String, String>
    ): Boolean {
        val columnValue = row.getValue(condition.columnName) ?: return false
        val targetValue = condition.value
        val columnType = dataType[condition.columnName]?.uppercase() ?: "VARCHAR"

        return when (condition.operator) {
            Condition.Operator.EQUAL -> {
                compareValues(columnValue, targetValue, columnType) == 0
            }
            Condition.Operator.NOT_EQUAL -> {
                compareValues(columnValue, targetValue, columnType) != 0
            }
            Condition.Operator.GREATER_THAN -> {
                compareValues(columnValue, targetValue, columnType) > 0
            }
            Condition.Operator.LESS_THAN -> {
                compareValues(columnValue, targetValue, columnType) < 0
            }
            Condition.Operator.GREATER_OR_EQUAL -> {
                compareValues(columnValue, targetValue, columnType) >= 0
            }
            Condition.Operator.LESS_OR_EQUAL -> {
                compareValues(columnValue, targetValue, columnType) <= 0
            }
            Condition.Operator.LIKE -> {
                // TODO: LIKE 패턴 매칭 구현 (나중에)
                columnValue.contains(targetValue.replace("%", ""))
            }
        }
    }

    /**
     * 타입에 맞게 값을 비교
     *
     * @return 0이면 같음, 양수면 left > right, 음수면 left < right
     */
    private fun compareValues(left: String, right: String, columnType: String): Int {
        return when (columnType) {
            "INT" -> {
                val leftInt = left.toIntOrNull() ?: 0
                val rightInt = right.toIntOrNull() ?: 0
                leftInt.compareTo(rightInt)
            }
            "TIMESTAMP" -> {
                // 타임스탬프는 숫자로 비교
                val leftLong = left.toLongOrNull() ?: 0L
                val rightLong = right.toLongOrNull() ?: 0L
                leftLong.compareTo(rightLong)
            }
            "BOOLEAN" -> {
                val leftBool = left.toBoolean()
                val rightBool = right.toBoolean()
                leftBool.compareTo(rightBool)
            }
            else -> {
                // VARCHAR는 문자열 비교
                left.compareTo(right)
            }
        }
    }
}
