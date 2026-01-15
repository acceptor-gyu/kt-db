package study.db.server

import org.slf4j.LoggerFactory
import study.db.server.db_engine.ConnectionHandler
import study.db.server.db_engine.ConnectionManager
import study.db.server.service.TableService
import study.db.server.elasticsearch.service.ExplainService
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * DbTcpServer - TCP 연결을 수락하고 ConnectionHandler에 위임하는 서버
 *
 * [Connection ID 관리]
 * - ConnectionManager가 Connection ID 생성 및 관리
 * - AtomicLong을 사용하여 Thread-safe하게 ID 생성
 * - MySQL에서는 서버 재시작 시 1부터 다시 시작함
 *
 * [TableService 공유]
 * - TableService는 모든 연결에서 공유 (일단 in-memory DB로 구현)
 * - Thread-safe하게 구현 필요 (ConcurrentHashMap 등 사용)
 *
 * [ConnectionManager 기능]
 * - Connection ID 생성
 * - 활성 연결 추적 및 관리
 * - SHOW PROCESSLIST 구현 가능
 * - KILL <id> 명령으로 특정 연결 종료 가능
 * - 서버 종료 시 모든 연결 graceful shutdown
 *
 * [Connection Pool 관리]
 * - maxConnections: 최대 동시 연결 수 제한 (기본값 10)
 * - 최대 연결 수 초과 시 ConnectionPoolExhaustedException 발생
 * - MySQL의 max_connections 설정과 유사
 */
class DbTcpServer(
    private val port: Int,
    private val maxConnections: Int = 10,
    private val explainService: ExplainService? = null  // EXPLAIN 명령 지원 (optional)
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DbTcpServer::class.java)
    }

    val executor = Executors.newFixedThreadPool(maxConnections)

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    private val sharedTableService = TableService()

    /**
     * 활성 연결을 추적하고 관리하는 ConnectionManager
     * - Connection ID 생성 및 관리
     * - 연결 등록/해제
     * - 활성 연결 수 조회
     * - SHOW PROCESSLIST 지원
     * - KILL 명령 지원
     */
    private val connectionManager = ConnectionManager()

    fun start() {
        serverSocket = ServerSocket(port)
        running = true

        logger.info("DB Server started on port {} (max connections: {})", port, maxConnections)

        while (running) {
            try {
                // 여기서 TCP handshake 포함
                val clientSocket = serverSocket?.accept() ?: break

                // 최대 연결 수 체크
                if (connectionManager.getActiveCount() >= maxConnections) {
                    try {
                        // 클라이언트에게 에러 메시지 전송 후 소켓 닫기
                        clientSocket.getOutputStream().write("ERROR: Too many connections\n".toByteArray())
                        clientSocket.close()
                        logger.warn("Connection rejected: max connections ({}) reached", maxConnections)
                    } catch (e: Exception) {
                        logger.error("Failed to send rejection message: {}", e.message)
                    }
                    continue
                }

                // ConnectionManager로부터 새 연결 ID 생성
                val connectionId = connectionManager.generateConnectionId()

                // ConnectionHandler 생성
                val handler = ConnectionHandler(
                    connectionId = connectionId,
                    socket = clientSocket,
                    tableService = sharedTableService,
                    connectionManager = connectionManager,
                    explainService = explainService
                )

                try {
                    // 먼저 executor에 submit 시도
                    executor.submit(handler)

                    // 성공한 경우에만 ConnectionManager에 연결 등록
                    connectionManager.register(handler)

                    logger.info("New connection accepted: id={}, remote={}", connectionId, clientSocket.remoteSocketAddress)
                } catch (e: RejectedExecutionException) {
                    // executor가 shutdown 되었거나 큐가 가득 찬 경우
                    logger.warn("Connection rejected: executor not available - {}", e.message)
                    try {
                        clientSocket.getOutputStream().write("ERROR: Server is shutting down or overloaded\n".toByteArray())
                        clientSocket.close()
                    } catch (closeException: Exception) {
                        logger.error("Failed to close rejected socket: {}", closeException.message)
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    logger.error("Error accepting connection: {}", e.message)
                }
            }
        }
    }

    fun stop() {
        logger.info("DB Server stopping...")
        running = false

        // 1. 먼저 ServerSocket 닫기 (새 연결 차단)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            logger.error("Error closing server socket: {}", e.message)
        }

        // 2. 모든 활성 연결 graceful shutdown
        logger.info("Closing all active connections (count: {})...", connectionManager.getActiveCount())
        connectionManager.closeAll()

        // 3. executor graceful shutdown
        logger.info("Shutting down executor...")
        executor.shutdown()
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Executor did not terminate in time, forcing shutdown...")
                executor.shutdownNow()
                // 인터럽트 응답 대기
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("Executor did not terminate after force shutdown")
                }
            }
        } catch (e: InterruptedException) {
            logger.warn("Shutdown interrupted, forcing shutdown...")
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }

        logger.info("DB Server stopped")
    }

    /**
     * 현재 활성 연결 수 조회
     */
    fun getActiveConnectionCount(): Int {
        return connectionManager.getActiveCount()
    }
}
