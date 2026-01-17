package study.db.server.exception

import org.slf4j.LoggerFactory
import study.db.common.protocol.DbResponse

/**
 * ExceptionMapper - 예외를 DbResponse로 변환하는 중앙화된 예외 처리기
 *
 * Spring의 @ControllerAdvice/@ExceptionHandler 패턴을 참고하여 설계됨.
 * 반복되는 try-catch 블록을 제거하고 예외 처리 로직을 한 곳에 집중.
 */
object ExceptionMapper {
    private val logger = LoggerFactory.getLogger(ExceptionMapper::class.java)

    /**
     * 예외를 DbResponse로 매핑
     *
     * @param e 발생한 예외
     * @param connectionId 연결 ID (로깅용)
     * @return 적절한 에러 코드와 메시지를 포함한 DbResponse
     */
    fun mapToResponse(e: Exception, connectionId: Long? = null): DbResponse {
        return when (e) {
            // 409 - Conflict (리소스 중복)
            // ResourceAlreadyExistsException은 IllegalStateException의 서브클래스이므로
            // IllegalStateException보다 먼저 체크해야 함
            is ResourceAlreadyExistsException -> DbResponse(
                success = false,
                message = "${e.resourceType} '${e.resourceName}' already exists",
                errorCode = 409
            )

            // 400 - Bad Request (잘못된 요청)
            is ColumnNotFoundException -> DbResponse(
                success = false,
                message = "Column '${e.columnName}' does not exist in table '${e.tableName}'",
                errorCode = 400
            )

            is TypeMismatchException -> DbResponse(
                success = false,
                message = "Type mismatch for column: value='${e.value}', expected type='${e.expectedType}'. ${e.reason}",
                errorCode = 400
            )

            is UnsupportedTypeException -> DbResponse(
                success = false,
                message = e.message ?: "Unsupported data type",
                errorCode = 400
            )

            is IllegalArgumentException -> DbResponse(
                success = false,
                message = e.message ?: "Invalid argument",
                errorCode = 400
            )

            // 404 - Not Found (리소스 없음)
            is IllegalStateException -> DbResponse(
                success = false,
                message = e.message ?: "Resource not found",
                errorCode = 404
            )

            // 500 - Internal Server Error (내부 에러)
            else -> {
                connectionId?.let {
                    logger.error("Unexpected error for connection $it", e)
                } ?: logger.error("Unexpected error", e)

                DbResponse(
                    success = false,
                    message = "Internal error: ${e.message}",
                    errorCode = 500
                )
            }
        }
    }

    /**
     * Higher-order function으로 예외 처리를 래핑
     *
     * @param connectionId 연결 ID (로깅용)
     * @param block 실행할 비즈니스 로직
     * @return 성공 시 block의 결과, 실패 시 예외를 DbResponse로 변환
     *
     * 사용 예:
     * ```kotlin
     * return executeWithExceptionHandling(connectionId) {
     *     tableService.insert(tableName, values)
     *     DbResponse(success = true, message = "Data inserted")
     * }
     * ```
     */
    inline fun executeWithExceptionHandling(
        connectionId: Long? = null,
        block: () -> DbResponse
    ): DbResponse {
        return try {
            block()
        } catch (e: Exception) {
            mapToResponse(e, connectionId)
        }
    }
}
