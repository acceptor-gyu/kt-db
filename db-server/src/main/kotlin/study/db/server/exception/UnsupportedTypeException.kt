package study.db.server.exception

/**
 * 지원하지 않는 데이터 타입일 때 발생하는 예외
 *
 * @param message 에러 메시지
 */
class UnsupportedTypeException(
    message: String
) : IllegalArgumentException(message)
