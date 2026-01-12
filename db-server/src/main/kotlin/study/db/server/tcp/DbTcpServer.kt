package study.db.server.tcp

import study.db.server.DbServer
import study.db.server.engine.ConnectionHandler
import study.db.server.engine.ConnectionManager
import study.db.server.service.TableService
import java.net.ServerSocket

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
 */
class DbTcpServer(
    private val port: Int,
    private val dbServer: DbServer = DbServer()
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    /**
     * 모든 연결이 공유하는 TableService
     * TODO: Thread-safe하게 구현 확인 필요
     */
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

        println("DB Server started on port $port")

        while (running) {
            try {
                // 여기서 TCP handshake 포함
                val clientSocket = serverSocket?.accept() ?: break

                // ConnectionManager로부터 새 연결 ID 생성
                val connectionId = connectionManager.generateConnectionId()

                // ConnectionHandler 생성 및 스레드 풀에서 실행
                val handler = ConnectionHandler(
                    connectionId = connectionId,
                    socket = clientSocket,
                    tableService = sharedTableService,
                    connectionManager = connectionManager
                )

                // ConnectionManager에 연결 등록
                connectionManager.register(handler)

                dbServer.executor.submit(handler)

                println("New connection accepted: id=$connectionId, remote=${clientSocket.remoteSocketAddress}")
            } catch (e: Exception) {
                if (running) {
                    println("Error accepting connection: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        serverSocket?.close()

        // TODO: 모든 활성 연결 graceful shutdown
        // connectionManager.closeAll()

        // TODO: executor graceful shutdown
        // dbServer.executor.shutdown()
        // dbServer.executor.awaitTermination(30, TimeUnit.SECONDS)

        println("DB Server stopped")
    }
}
