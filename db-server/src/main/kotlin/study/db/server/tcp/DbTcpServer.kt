package study.db.server.tcp

import study.db.server.DbServer
import study.db.server.engine.ConnectionHandler
import study.db.server.service.TableService
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

/**
 * DbTcpServer - TCP 연결을 수락하고 ConnectionHandler에 위임하는 서버
 *
 * TODO [Connection ID 생성 가이드]
 * - connectionIdGenerator: 각 연결에 고유한 ID를 부여
 * - AtomicLong을 사용하여 Thread-safe하게 ID 생성
 * - MySQL에서는 서버 재시작 시 1부터 다시 시작함
 *
 * TODO [TableService 공유 가이드]
 * - TableService는 모든 연결에서 공유해야 함 (in-memory DB이므로)
 * - Thread-safe하게 구현 필요 (ConcurrentHashMap 등 사용)
 * - 현재는 ConnectionHandler마다 별도 인스턴스 생성 → 데이터 공유 안 됨 (버그)
 *
 * TODO [ConnectionManager 추가 고려]
 * - 활성 연결 추적: Map<Long, ConnectionHandler>
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
     * Connection ID 생성기
     * - incrementAndGet()으로 Thread-safe하게 고유 ID 생성
     * - 첫 번째 연결은 ID 1번
     */
    private val connectionIdGenerator = AtomicLong(0)

    /**
     * 모든 연결이 공유하는 TableService
     * TODO: Thread-safe하게 구현 확인 필요
     */
    private val sharedTableService = TableService()

    fun start() {
        serverSocket = ServerSocket(port)
        running = true

        println("DB Server started on port $port")

        while (running) {
            try {
                // 여기서 TCP handshake 포함
                val clientSocket = serverSocket?.accept() ?: break

                // 새 연결에 고유 ID 부여
                val connectionId = connectionIdGenerator.incrementAndGet()

                // ConnectionHandler 생성 및 스레드 풀에서 실행
                val handler = ConnectionHandler(
                    connectionId = connectionId,
                    socket = clientSocket,
                    tableService = sharedTableService
                )

                // TODO: ConnectionManager가 있다면 여기서 등록
                // connectionManager.register(connectionId, handler)

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
