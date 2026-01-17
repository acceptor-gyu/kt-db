package study.db.server.validation

import study.db.server.exception.TypeMismatchException
import study.db.server.exception.UnsupportedTypeException

/**
 * TypeValidator - 데이터 타입 검증 유틸리티
 *
 * 지원 타입: INT, VARCHAR, TIMESTAMP, BOOLEAN
 * 엄격한 타입 검증으로 잘못된 데이터 입력 방지
 */
object TypeValidator {

    /**
     * 지원되는 데이터 타입
     */
    enum class DataType {
        INT,
        VARCHAR,
        TIMESTAMP,
        BOOLEAN;

        companion object {
            fun fromString(type: String): DataType? {
                return values().find { it.name.equals(type, ignoreCase = true) }
            }
        }
    }

    /**
     * 값이 지정된 타입과 일치하는지 검증
     *
     * @param value 검증할 값
     * @param type 데이터 타입 (INT, VARCHAR, TIMESTAMP, BOOLEAN)
     * @throws TypeMismatchException 타입이 일치하지 않을 때
     * @throws UnsupportedTypeException 지원하지 않는 타입일 때
     */
    fun validate(value: String, type: String) {
        val dataType = DataType.fromString(type)
            ?: throw UnsupportedTypeException("Unsupported data type: $type")

        when (dataType) {
            DataType.INT -> validateInt(value, type)
            DataType.VARCHAR -> validateVarchar(value, type)
            DataType.TIMESTAMP -> validateTimestamp(value, type)
            DataType.BOOLEAN -> validateBoolean(value, type)
        }
    }

    /**
     * INT 타입 검증
     * 32비트 정수 범위: -2,147,483,648 ~ 2,147,483,647
     */
    private fun validateInt(value: String, type: String) {
        try {
            value.toInt()
        } catch (e: NumberFormatException) {
            throw TypeMismatchException(
                value = value,
                expectedType = type,
                reason = "Value cannot be parsed as INT"
            )
        }
    }

    /**
     * VARCHAR 타입 검증
     * 모든 문자열 허용 (항상 성공)
     */
    private fun validateVarchar(value: String, type: String) {
        // VARCHAR는 모든 문자열을 허용
        // 필요시 길이 제한 추가 가능 (예: VARCHAR(255))
    }

    /**
     * TIMESTAMP 타입 검증
     * ISO-8601 형식: YYYY-MM-DDTHH:mm:ss or YYYY-MM-DD HH:mm:ss
     */
    private fun validateTimestamp(value: String, type: String) {
        try {
            java.time.Instant.parse(value)
        } catch (e: Exception) {
            // ISO-8601 파싱 실패 시 다른 형식 시도
            try {
                java.time.LocalDateTime.parse(value.replace(" ", "T"))
            } catch (e2: Exception) {
                throw TypeMismatchException(
                    value = value,
                    expectedType = type,
                    reason = "Value cannot be parsed as TIMESTAMP (expected format: YYYY-MM-DDTHH:mm:ss)"
                )
            }
        }
    }

    /**
     * BOOLEAN 타입 검증
     * 허용값: true, false (대소문자 무시)
     */
    private fun validateBoolean(value: String, type: String) {
        if (!value.equals("true", ignoreCase = true) &&
            !value.equals("false", ignoreCase = true)) {
            throw TypeMismatchException(
                value = value,
                expectedType = type,
                reason = "Value must be 'true' or 'false' (case insensitive)"
            )
        }
    }
}
