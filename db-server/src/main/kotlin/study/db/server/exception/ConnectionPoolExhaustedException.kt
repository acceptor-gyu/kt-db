package study.db.server.exception

/**
 * Connection Pool이 가득 찼을 때 발생하는 예외
 *
 * MySQL의 "Too many connections" 에러와 유사
 * - 에러 코드: ER_CON_COUNT_ERROR (1040)
 * - max_connections 설정값을 초과한 경우 발생
 *
 * @param maxConnections 설정된 최대 연결 수
 * @param currentConnections 현재 활성 연결 수
 */
class ConnectionPoolExhaustedException(
    val maxConnections: Int,
    val currentConnections: Int
) : RuntimeException(
    "Too many connections: current=$currentConnections, max=$maxConnections"
)
