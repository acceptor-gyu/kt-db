package study.db.server.db_engine

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ConnectionManager - 활성 연결을 추적하고 관리하는 매니저
 *
 * 주요 기능:
 * - Connection ID 생성 및 관리 (AtomicLong 기반 Thread-safe)
 * - SHOW PROCESSLIST: 현재 연결된 모든 클라이언트 목록 조회
 * - KILL <id>: 특정 연결 강제 종료
 * - 서버 종료 시 모든 연결 graceful shutdown
 * - 연결 통계 (활성 연결 수, 총 연결 수 등)
 *
 * 사용 예시:
 * ```
 * // DbTcpServer에서
 * val connectionManager = ConnectionManager()
 *
 * // 새 연결 시
 * val connectionId = connectionManager.generateConnectionId()
 * val handler = ConnectionHandler(connectionId, socket, tableService, connectionManager)
 * connectionManager.register(handler)
 *
 * // ConnectionHandler 종료 시 (자동 호출됨)
 * connectionManager.unregister(connectionId)
 *
 * // 서버 종료 시
 * connectionManager.closeAll()
 * ```
 */
class ConnectionManager {

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectionManager::class.java)
    }

    /**
     * Connection ID 생성기
     * - incrementAndGet()으로 Thread-safe하게 고유 ID 생성
     * - 첫 번째 연결은 ID 1번
     * - MySQL에서는 서버 재시작 시 1부터 다시 시작함
     */
    private val connectionIdGenerator = AtomicLong(0)

    /**
     * 활성 연결 저장소
     * - Key: connectionId (Long)
     * - Value: ConnectionHandler
     * - ConcurrentHashMap 사용으로 Thread-safe 보장
     * - ConnectionHandler 직접 저장으로 kill() 등의 제어 가능
     */
    private val connections = ConcurrentHashMap<Long, ConnectionHandler>()

    /**
     * 총 연결 수 (통계용)
     *
     * TODO [구현 가이드]
     * - 서버 시작 이후 총 연결 수
     * - AtomicLong 사용 권장
     * - SHOW STATUS에서 'Connections' 값으로 사용
     */
    // private val totalConnections = AtomicLong(0)

    /**
     * 새 연결을 위한 고유 ID 생성
     *
     * @return 생성된 연결 ID
     */
    fun generateConnectionId(): Long {
        return connectionIdGenerator.incrementAndGet()
    }

    /**
     * 새 연결 등록
     *
     * @param handler 등록할 ConnectionHandler
     */
    fun register(handler: ConnectionHandler) {
        connections[handler.connectionId] = handler
        logger.info("Connection ${handler.connectionId} registered (active: ${getActiveCount()})")
    }

    /**
     * 연결 등록 해제
     *
     * @param connectionId 해제할 연결 ID
     */
    fun unregister(connectionId: Long) {
        connections.remove(connectionId)
        logger.info("Connection $connectionId unregistered (active: ${getActiveCount()})")
    }

    /**
     * 특정 연결 조회
     *
     * @param connectionId 조회할 연결 ID
     * @return ConnectionHandler 또는 null
     */
    fun get(connectionId: Long): ConnectionHandler? {
        return connections[connectionId]
    }

    /**
     * 활성 연결 수 조회
     *
     * @return 현재 활성 연결 수 (SHOW STATUS의 'Threads_connected' 값)
     */
    fun getActiveCount(): Int {
        return connections.size
    }

    /**
     * 모든 활성 연결 목록 조회 (SHOW PROCESSLIST)
     *
     * 반환할 정보:
     * - Id: connectionId
     * - User: 인증된 사용자명 (ConnectionHandler에 추가 필요)
     * - Host: 클라이언트 IP:Port (socket.remoteSocketAddress)
     * - db: 현재 선택된 데이터베이스 (USE 명령 구현 시)
     * - Command: 현재 실행 중인 명령 (Query, Sleep 등)
     * - Time: 현재 상태 지속 시간 (초)
     * - State: 현재 상태 설명
     * - Info: 실행 중인 쿼리 (있는 경우)
     *
     * ConnectionHandler에 추가 필요한 필드:
     * ```
     * val connectedAt: Instant = Instant.now()
     * var currentUser: String? = null
     * var currentCommand: String? = null
     * var lastActivityAt: Instant = Instant.now()
     * ```
     *
     * @return 연결 정보 목록
     */
    fun getAllConnections(): List<ConnectionInfo> {
        // TODO: 구현
        // return connections.values.map { handler ->
        //     ConnectionInfo(
        //         id = handler.connectionId,
        //         user = handler.currentUser,
        //         host = handler.socket.remoteSocketAddress.toString(),
        //         ...
        //     )
        // }
        return emptyList()
    }

    /**
     * 특정 연결 강제 종료 (KILL 명령)
     *
     * TODO [구현 가이드]
     * 1. connections에서 해당 handler 조회
     * 2. 없으면 false 반환 (또는 예외)
     * 3. handler의 소켓 close() 호출
     *    - 소켓을 닫으면 readUTF()에서 예외 발생 → finally에서 정리
     * 4. connections에서 제거
     * 5. 로그 출력: "Connection $connectionId killed"
     *
     * 주의사항:
     * - 자기 자신을 kill하는 경우 처리
     * - kill 권한 체크 (root만 다른 사용자 kill 가능 등)
     *
     * @param connectionId 종료할 연결 ID
     * @return 성공 여부
     */
    fun kill(connectionId: Long): Boolean {
        // TODO: 구현
        // val handler = connections[connectionId] ?: return false
        // try {
        //     handler.socket.close()  // socket을 public으로 변경 또는 close 메서드 제공
        // } catch (e: Exception) {
        //     // 이미 닫혔을 수 있음
        // }
        // connections.remove(connectionId)
        // return true
        return false
    }

    /**
     * 모든 연결 종료 (서버 종료 시)
     *
     * 구현 방식:
     * 1. 모든 연결의 소켓을 닫아서 graceful shutdown 유도
     * 2. 일정 시간 대기하면서 연결이 자연스럽게 종료되기를 기다림
     * 3. 아직 남은 연결이 있다면 강제로 제거
     *
     * @param gracefulTimeoutMs graceful shutdown 대기 시간 (기본 5초)
     */
    fun closeAll(gracefulTimeoutMs: Long = 5000) {
        if (connections.isEmpty()) {
            logger.info("No connections to close")
            return
        }

        val initialCount = connections.size
        logger.info("Closing $initialCount connections...")

        // 1. 모든 연결의 소켓 닫기 (이렇게 하면 ConnectionHandler의 run()에서 예외 발생 → finally에서 정리)
        connections.values.forEach { handler ->
            try {
                handler.closeSocket()
            } catch (e: Exception) {
                logger.warn("Failed to close socket for connection ${handler.connectionId}: ${e.message}")
            }
        }

        // 2. graceful 대기 - 연결들이 자연스럽게 정리되기를 기다림
        val deadline = System.currentTimeMillis() + gracefulTimeoutMs
        while (connections.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            logger.debug("Waiting for connections to close... (${connections.size} remaining)")
        }

        // 3. 아직 남은 연결이 있다면 강제로 제거
        if (connections.isNotEmpty()) {
            logger.warn("Forcefully removing ${connections.size} remaining connections")
            connections.clear()
        }

        logger.info("All connections closed (initially: $initialCount)")
    }

    /**
     * 연결 통계 조회
     *
     * TODO [구현 가이드]
     * MySQL SHOW STATUS 스타일의 통계:
     * - Connections: 총 연결 시도 수
     * - Threads_connected: 현재 연결 수
     * - Threads_running: 현재 쿼리 실행 중인 연결 수
     * - Max_used_connections: 동시 최대 연결 수
     *
     * @return 통계 Map
     */
    fun getStats(): Map<String, Long> {
        // TODO: 구현
        // return mapOf(
        //     "Connections" to totalConnections.get(),
        //     "Threads_connected" to connections.size.toLong(),
        //     ...
        // )
        return emptyMap()
    }
}

/**
 * 연결 정보 DTO (SHOW PROCESSLIST 결과용)
 *
 * TODO [구현 가이드]
 * MySQL SHOW PROCESSLIST 출력 형식:
 * +----+------+-----------+------+---------+------+-------+------------------+
 * | Id | User | Host      | db   | Command | Time | State | Info             |
 * +----+------+-----------+------+---------+------+-------+------------------+
 */
data class ConnectionInfo(
    val id: Long,
    val user: String?,
    val host: String,
    val db: String?,
    val command: String,    // Query, Sleep, Connect, etc.
    val time: Long,         // 현재 상태 지속 시간 (초)
    val state: String?,
    val info: String?       // 실행 중인 쿼리
)
