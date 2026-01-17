package study.db.server.exception

/**
 * 데이터 타입이 일치하지 않을 때 발생하는 예외
 *
 * @param value 검증에 실패한 값
 * @param expectedType 기대한 데이터 타입
 * @param reason 실패 이유
 */
class TypeMismatchException(
    val value: String,
    val expectedType: String,
    val reason: String
) : IllegalArgumentException(
    "Type mismatch: value='$value', expectedType='$expectedType', reason='$reason'"
)
