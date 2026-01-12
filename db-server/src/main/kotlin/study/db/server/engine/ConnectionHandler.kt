package study.db.server.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import study.db.common.Table
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest
import study.db.common.protocol.DbResponse
import study.db.common.protocol.ProtocolCodec
import study.db.server.service.TableService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.*

/**
 * ConnectionHandler - 개별 클라이언트 연결을 처리하는 핸들러
 *
 * MySQL Connection Handler의 역할:
 * 1. 핸드셰이크 → 인증 → 명령 처리의 상태 기반 흐름 관리
 * 2. 각 연결에 고유 ID 부여 (디버깅, 모니터링, KILL 명령에 필요)
 * 3. 연결 리소스의 안전한 정리 (Graceful Shutdown)
 *
 * @param connectionId 이 연결의 고유 식별자
 *                     - MySQL의 CONNECTION_ID()에 해당
 *                     - AtomicLong 등으로 생성하여 전달받음
 *                     - 로그, SHOW PROCESSLIST, KILL 명령에 사용
 * @param socket 클라이언트 소켓
 * @param tableService 테이블 서비스 (모든 연결이 공유)
 *                     - 주의: Thread-safe하게 구현되어야 함
 *
 * TODO [Connection ID 구현 가이드]
 * 1. DbTcpServer 또는 ConnectionManager에서 AtomicLong으로 ID 생성
 *    ```
 *    class DbTcpServer {
 *        private val connectionIdGenerator = AtomicLong(0)
 *
 *        fun accept() {
 *            val connId = connectionIdGenerator.incrementAndGet()
 *            ConnectionHandler(connId, socket, tableService)
 *        }
 *    }
 *    ```
 * 2. 모든 로그 메시지에 connectionId 포함
 * 3. SHOW PROCESSLIST 구현 시 활용
 * 4. KILL <id> 명령 구현 시 활용
 */
class ConnectionHandler(
    val connectionId: Long,  // TODO: 현재 사용 안 됨 - DbTcpServer에서 생성하여 전달 필요
    private val socket: Socket,
    private val tableService: TableService  // TODO: 외부에서 주입받도록 변경 (Thread-safe 공유)
) : Runnable {

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectionHandler::class.java)
    }

    val properties = Properties()
    private val json = Json { encodeDefaults = true }

    init {
        try {
            val inputStream = this::class.java.classLoader.getResourceAsStream("application.properties")
                ?: throw IOException("application.properties 파일을 찾을 수 없습니다.")
            properties.load(inputStream)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private val authenticatedUser = properties.getProperty("default.user") ?: ""
    private val authenticatedPassword = properties.getProperty("default.password") ?: ""

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    private var state = ConnectionState.CONNECTED

    /**
     * 연결 처리 메인 루프
     *
     * TODO [Graceful Shutdown 구현 가이드]
     * 현재 문제점:
     * - 기존 코드에서 executor.shutdown()을 호출하면 모든 연결이 종료됨
     * - ExecutorService는 모든 ConnectionHandler가 공유하는 스레드 풀
     * - 한 연결의 예외가 전체 서버에 영향을 주면 안 됨
     *
     * 올바른 리소스 정리 원칙:
     * 1. 개별 연결의 예외는 해당 연결만 정리 (executor는 건드리지 않음)
     * 2. finally 블록에서 반드시 리소스 정리
     * 3. 각 리소스(input, output, socket)를 개별적으로 close
     * 4. close 실패해도 다음 리소스 정리 계속 진행
     *
     * 추가 개선 사항:
     * - 연결 종료 시 ConnectionManager에서 등록 해제
     * - 로그에 connectionId 포함하여 추적 용이하게
     */
    override fun run() {
        try {
            // application level handshake
            sendHandshake()
            state = ConnectionState.HANDSHAKE_SENT

            while (!socket.isClosed) {
                val message = input.readUTF()
                handleMessage(message)
            }
        } catch (e: java.io.EOFException) {
            // 클라이언트가 정상적으로 연결을 종료한 경우
            logger.info("Connection $connectionId closed by client")
        } catch (e: java.net.SocketException) {
            // 소켓 관련 예외 (연결 리셋 등)
            logger.warn("Connection $connectionId socket error: ${e.message}")
        } catch (e: Exception) {
            // 기타 예외
            logger.error("Connection $connectionId unexpected error: ${e.message}", e)
        } finally {
            // ⚠️ 중요: executor.shutdown()을 여기서 호출하면 안 됨!
            // executor는 서버 전체에서 공유하는 스레드 풀이므로
            // 개별 연결 종료 시에는 해당 연결의 리소스만 정리해야 함
            close()
        }
    }

    /**
     * 연결 리소스 정리
     *
     * TODO [리소스 정리 구현 가이드]
     * 1. ConnectionManager가 있다면 등록 해제
     *    ```
     *    connectionManager.unregister(connectionId)
     *    ```
     * 2. 각 리소스를 개별 try-catch로 감싸서 하나가 실패해도 나머지 정리 계속
     * 3. 정리 완료 로그 남기기
     */
    private fun close() {
        // TODO: connectionManager.unregister(connectionId)

        try {
            input.close()
        } catch (e: IOException) {
            logger.warn("Failed to close input stream for connection $connectionId: ${e.message}")
        }

        try {
            output.close()
        } catch (e: IOException) {
            logger.warn("Failed to close output stream for connection $connectionId: ${e.message}")
        }

        try {
            if (!socket.isClosed) {
                socket.close()
            }
        } catch (e: IOException) {
            logger.warn("Failed to close socket for connection $connectionId: ${e.message}")
        }

        logger.info("Connection $connectionId closed successfully")
    }

    /**
     * 클라이언트에게 초기 핸드셰이크 메시지 전송
     * TODO: 실제 프로토콜에 맞는 핸드셰이크 메시지 구현 필요
     */
    private fun sendHandshake() {
        output.writeUTF("HANDSHAKE|v1.0")
        output.flush()
    }

    private fun handleMessage(message: String) {
        when (state) {
            ConnectionState.HANDSHAKE_SENT -> handleAuth(message)
            ConnectionState.AUTHENTICATED -> handleCommand(message)
            else -> protocolError()
        }
    }

    /**
     * 인증 완료 후 클라이언트 명령 처리
     * TODO: 실제 명령 파싱 및 처리 로직 구현 필요
     * 현재는 ProtocolCodec 기반의 handleClient/processRequest 메서드가 별도로 구현되어 있음
     */
    private fun handleCommand(message: String) {
        // 추후 실제 명령어 처리 로직 구현
        // 예: CREATE TABLE, INSERT, SELECT 등의 SQL 명령어 파싱 및 실행
        output.writeUTF("Command received: $message")
        output.flush()
    }

    /**
     * 학습을 위한 프로젝트로 인증/인가 검증은 간단하게 처리
     */
    private fun handleAuth(message: String) {
        if (!message.startsWith("AUTH|")) {
            protocolError()
        }

        val parts = message.split("|")
        val user = parts[1]
        val password = parts[2]

        if (user != authenticatedUser || password != authenticatedPassword) {
            protocolError()
            return
        }

        output.writeUTF("Authenticated")
        output.flush()

        state = ConnectionState.AUTHENTICATED
        // Session이 있다면, 이 부분에서 session 생성
    }

    private fun protocolError() {
        output.writeUTF("ER_NOT_SUPPORTED_AUTH_MODE")
        output.flush()
        socket.close()
    }

    /**
     * TODO: Parser쪽으로 역할 분리
     */

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val requestBytes = ProtocolCodec.readMessage(client.getInputStream())
                val request = ProtocolCodec.decodeRequest(requestBytes)

                val response = processRequest(request)

                val responseBytes = ProtocolCodec.encodeResponse(response)
                ProtocolCodec.writeMessage(client.getOutputStream(), responseBytes)
            } catch (e: Exception) {
                val errorResponse = DbResponse(
                    success = false,
                    message = "Server error: ${e.message}",
                    errorCode = 500
                )
                try {
                    val responseBytes = ProtocolCodec.encodeResponse(errorResponse)
                    ProtocolCodec.writeMessage(client.getOutputStream(), responseBytes)
                } catch (_: Exception) {
                    // Ignore if we can't send error response
                }
            }
        }
    }

    private fun processRequest(request: DbRequest): DbResponse {
        return when (request.command) {
            DbCommand.CREATE_TABLE -> handleCreateTable(request)
            DbCommand.INSERT -> handleInsert(request)
            DbCommand.SELECT -> handleSelect(request)
            DbCommand.DELETE -> handleDelete(request)
            DbCommand.DROP_TABLE -> handleDropTable(request)
            DbCommand.PING -> DbResponse(success = true, message = "pong")
        }
    }

    private fun handleCreateTable(request: DbRequest): DbResponse {
        val tableName = request.tableName
            ?: return DbResponse(success = false, message = "Table name is required", errorCode = 400)
        val columns = request.columns
            ?: return DbResponse(success = false, message = "Columns are required", errorCode = 400)

        if (tableService.tableExists(tableName)) {
            return DbResponse(success = false, message = "Table '$tableName' already exists", errorCode = 409)
        }

        val query = tableService.createTable(tableName, columns)
        return DbResponse(success = true, message = "Table created", data = query)
    }

    private fun handleInsert(request: DbRequest): DbResponse {
        val tableName = request.tableName
            ?: return DbResponse(success = false, message = "Table name is required", errorCode = 400)
        val values = request.values
            ?: return DbResponse(success = false, message = "Values are required", errorCode = 400)

        val success = tableService.insert(tableName, values)
        return if (success) {
            DbResponse(success = true, message = "Data inserted")
        } else {
            DbResponse(success = false, message = "Table '$tableName' not found", errorCode = 404)
        }
    }

    /**
     * SELECT 명령 처리 - 테이블 조회
     * Json 직렬화를 통해 Table 객체를 문자열로 변환하여 반환
     */
    private fun handleSelect(request: DbRequest): DbResponse {
        val tableName = request.tableName
            ?: return DbResponse(success = false, message = "Table name is required", errorCode = 400)

        val table = tableService.select(tableName)
        return if (table != null) {
            // Table 객체를 JSON 문자열로 직렬화
            DbResponse(success = true, data = json.encodeToString<Table>(table))
        } else {
            DbResponse(success = false, message = "Table '$tableName' not found", errorCode = 404)
        }
    }

    private fun handleDelete(request: DbRequest): DbResponse {
        return DbResponse(success = false, message = "DELETE not implemented yet", errorCode = 501)
    }

    private fun handleDropTable(request: DbRequest): DbResponse {
        val tableName = request.tableName
            ?: return DbResponse(success = false, message = "Table name is required", errorCode = 400)

        val success = tableService.dropTable(tableName)
        return if (success) {
            DbResponse(success = true, message = "Table '$tableName' dropped")
        } else {
            DbResponse(success = false, message = "Table '$tableName' not found", errorCode = 404)
        }
    }
}
