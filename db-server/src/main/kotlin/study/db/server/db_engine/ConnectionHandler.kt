package study.db.server.db_engine

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import study.db.common.Table
import study.db.common.protocol.DbResponse
import study.db.common.protocol.ProtocolCodec
import study.db.server.service.TableService
import study.db.server.elasticsearch.service.ExplainService
import study.db.server.elasticsearch.document.QueryPlan
import study.db.server.exception.ColumnNotFoundException
import study.db.server.exception.TypeMismatchException
import study.db.server.exception.UnsupportedTypeException
import study.db.server.exception.ExceptionMapper
import study.db.server.exception.ResourceAlreadyExistsException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.*

/**
 *
 * DB 엔진 흐름
 *
 * Parser      : 문법 검사, AST 생성
 *  ↓
 * Resolver    : 이름 해석, 타입 결정, 의미 확정
 *  ↓
 * Optimizer   : 실행 방법 선택
 *  ↓
 * Executor    : 실제 실행
 */

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
 *                     - ConnectionManager에서 생성하여 전달받음
 *                     - 로그, SHOW PROCESSLIST, KILL 명령에 사용
 * @param socket 클라이언트 소켓
 * @param tableService 테이블 서비스 (모든 연결이 공유)
 *                     - 주의: Thread-safe하게 구현되어야 함
 * @param connectionManager 연결 관리자 (optional)
 *                          - 연결 등록/해제 처리
 *                          - 종료 시 자동으로 unregister 호출
 *
 * [Connection ID 관리]
 * - ConnectionManager.generateConnectionId()로 ID 생성
 * - 모든 로그 메시지에 connectionId 포함
 * - SHOW PROCESSLIST 구현 시 활용
 * - KILL <id> 명령 구현 시 활용
 */
class ConnectionHandler(
    val connectionId: Long,  // ConnectionManager에서 생성된 고유 ID
    private val socket: Socket,
    private val tableService: TableService,  // 모든 연결이 공유하는 TableService (Thread-safe 구현 필요)
    private val connectionManager: ConnectionManager? = null,  // 연결 관리자 (optional)
    private val explainService: ExplainService? = null  // EXPLAIN 명령 처리 서비스
) : Runnable {

    companion object {
        private val logger = LoggerFactory.getLogger(ConnectionHandler::class.java)
    }

    val properties = Properties()
    private val json = Json { encodeDefaults = true }
    private val objectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * 클래스패스에서 application.properties 파일을 읽어서 인증 정보(user, password)를 로드합니다.
     *
     * - 파일을 찾지 못하면 IOException을 던지지만, catch 블록에서 경고 로그만 출력하고 계속 진행
     * - 이 경우 authenticatedUser, authenticatedPassword가 빈 문자열로 초기화됨
     * - 프로덕션 환경에서는 파일이 없으면 연결을 거부하거나 기본값을 명시적으로 설정하는 것이 더 안전
     *
     * TODO:
     * - 파일이 없으면 연결을 거부하도록 변경 고려
     */
    init {
        try {
            val inputStream = this::class.java.classLoader.getResourceAsStream("application.properties")
                ?: throw IOException("application.properties 파일을 찾을 수 없습니다.")
            properties.load(inputStream)
        } catch (e: IOException) {
            logger.warn("Failed to load application.properties for connection {}: {}", connectionId, e.message)
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
            // Use ProtocolCodec to communicate with clients
            while (!socket.isClosed) {
                val requestBytes = ProtocolCodec.readMessage(input)
                val sql = ProtocolCodec.decodeRequest(requestBytes)

                val response = processRequest(sql)

                val responseBytes = ProtocolCodec.encodeResponse(response)
                ProtocolCodec.writeMessage(output, responseBytes)
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
     * 1. ConnectionManager에서 등록 해제
     * 2. 각 리소스를 개별 try-catch로 감싸서 하나가 실패해도 나머지 정리 계속
     * 3. 정리 완료 로그 남기기
     */
    private fun close() {
        // ConnectionManager에서 등록 해제
        connectionManager?.unregister(connectionId)

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
     * 소켓 닫기 (외부에서 호출 가능)
     * - ConnectionManager.closeAll()에서 사용
     * - 소켓을 닫으면 run()의 readUTF()에서 예외 발생 → finally에서 정리
     */
    fun closeSocket() {
        try {
            if (!socket.isClosed) {
                socket.close()
            }
        } catch (e: IOException) {
            logger.warn("Failed to close socket for connection $connectionId: ${e.message}")
        }
    }

    /**
     * 클라이언트에게 초기 핸드셰이크 메시지 전송
     * TODO: 실제 프로토콜에 맞는 핸드셰이크 메시지 구현 필요
     */
    private fun sendHandshake() {
        logger.info("Connection $connectionId sending handshake")
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
     *
     * Privilege System (Parser -> Resolver -> Privilege System)
     */
    private fun handleAuth(message: String) {
        if (!message.startsWith("AUTH|")) {
            protocolError()
            return
        }

        val parts = message.split("|")
        if (parts.size < 3) {
            logger.warn("Connection $connectionId invalid auth format: expected 3 parts, got ${parts.size}")
            protocolError()
            return
        }

        val user = parts[1]
        val password = parts[2]

        if (user != authenticatedUser || password != authenticatedPassword) {
            logger.warn("Connection $connectionId authentication failed for user: $user")
            protocolError()
            return
        }

        logger.info("Connection $connectionId authenticated successfully as user: $user")
        output.writeUTF("Authenticated")
        output.flush()

        state = ConnectionState.AUTHENTICATED
        // Session이 있다면, 이 부분에서 session 생성
    }

    private fun protocolError() {
        logger.warn("Connection $connectionId protocol error, closing connection")
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
                val sql = ProtocolCodec.decodeRequest(requestBytes)

                val response = processRequest(sql)

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

    /**
     * SQL 문자열 처리 - SQL 문자열을 파싱하여 적절한 핸들러로 라우팅
     */
    private fun processRequest(sql: String): DbResponse {
        val trimmedSql = sql.trim().trimEnd(';')

        // Handle special PING command
        if (trimmedSql.equals("PING", ignoreCase = true)) {
            return DbResponse(success = true, message = "pong")
        }

        return when {
            trimmedSql.startsWith("CREATE TABLE", ignoreCase = true) -> parseAndHandleCreateTable(trimmedSql)
            trimmedSql.startsWith("INSERT INTO", ignoreCase = true) -> parseAndHandleInsert(trimmedSql)
            trimmedSql.startsWith("SELECT", ignoreCase = true) -> parseAndHandleSelect(trimmedSql)
            trimmedSql.startsWith("DROP TABLE", ignoreCase = true) -> parseAndHandleDropTable(trimmedSql)
            trimmedSql.startsWith("EXPLAIN", ignoreCase = true) -> parseAndHandleExplain(trimmedSql)
            else -> DbResponse(success = false, message = "Unsupported SQL query: $sql", errorCode = 400)
        }
    }

    /**
     * CREATE TABLE users (id INT, name VARCHAR)
     */
    private fun parseAndHandleCreateTable(sql: String): DbResponse {
        val regex = Regex("""CREATE\s+TABLE\s+(\w+)\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        val match = regex.find(sql)
            ?: return DbResponse(success = false, message = "Invalid CREATE TABLE syntax: $sql", errorCode = 400)

        val tableName = match.groupValues[1]
        val columnsPart = match.groupValues[2]

        // Parse columns: "id INT, name VARCHAR"
        val columns = mutableMapOf<String, String>()
        columnsPart.split(",").forEach { columnDef ->
            val parts = columnDef.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                columns[parts[0]] = parts[1].uppercase()
            }
        }

        return ExceptionMapper.executeWithExceptionHandling(connectionId) {
            if (tableService.tableExists(tableName)) {
                throw ResourceAlreadyExistsException("Table", tableName)
            }

            val query = tableService.createTable(tableName, columns)
            DbResponse(success = true, message = "Table created", data = query)
        }
    }

    /**
     * INSERT INTO users VALUES (id="1", name="John")
     */
    private fun parseAndHandleInsert(sql: String): DbResponse {
        val regex = Regex("""INSERT\s+INTO\s+(\w+)\s+VALUES\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
        val match = regex.find(sql)
            ?: return DbResponse(success = false, message = "Invalid INSERT syntax: $sql", errorCode = 400)

        val tableName = match.groupValues[1]
        val valuesPart = match.groupValues[2]

        // Parse values: id="1", name="John"
        val values = mutableMapOf<String, String>()
        val valueRegex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,)]+))""")
        valueRegex.findAll(valuesPart).forEach { valueMatch ->
            val columnName = valueMatch.groupValues[1]
            val value = valueMatch.groupValues[2].ifEmpty {
                valueMatch.groupValues[3].ifEmpty {
                    valueMatch.groupValues[4]
                }
            }
            values[columnName] = value
        }

        return ExceptionMapper.executeWithExceptionHandling(connectionId) {
            tableService.insert(tableName, values)
            DbResponse(success = true, message = "Data inserted")
        }
    }

    /**
     * SELECT * FROM users
     */
    private fun parseAndHandleSelect(sql: String): DbResponse {
        val regex = Regex("""SELECT\s+.+?\s+FROM\s+(\w+)(?:\s+WHERE\s+(.+))?""", RegexOption.IGNORE_CASE)
        val match = regex.find(sql)
            ?: return DbResponse(success = false, message = "Invalid SELECT syntax: $sql", errorCode = 400)

        val tableName = match.groupValues[1]
        // Note: condition is parsed but not currently used by tableService
        // val condition = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }

        return ExceptionMapper.executeWithExceptionHandling(connectionId) {
            val table = tableService.select(tableName)
                ?: throw IllegalStateException("Table '$tableName' not found")

            // Table 객체를 JSON 문자열로 직렬화
            DbResponse(success = true, data = json.encodeToString<Table>(table))
        }
    }

    /**
     * DROP TABLE users
     */
    private fun parseAndHandleDropTable(sql: String): DbResponse {
        val regex = Regex("""DROP\s+TABLE\s+(\w+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(sql)
            ?: return DbResponse(success = false, message = "Invalid DROP TABLE syntax: $sql", errorCode = 400)

        val tableName = match.groupValues[1]

        return ExceptionMapper.executeWithExceptionHandling(connectionId) {
            val success = tableService.dropTable(tableName)
            if (!success) {
                throw IllegalStateException("Table '$tableName' not found")
            }
            DbResponse(success = true, message = "Table '$tableName' dropped")
        }
    }

    /**
     * EXPLAIN SELECT * FROM users
     */
    private fun parseAndHandleExplain(sql: String): DbResponse {
        // ExplainService가 주입되지 않은 경우
        if (explainService == null) {
            return DbResponse(
                success = false,
                message = "EXPLAIN command is not available (ExplainService not configured)",
                errorCode = 503
            )
        }

        val regex = Regex("""EXPLAIN\s+(.+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(sql)
            ?: return DbResponse(success = false, message = "Invalid EXPLAIN syntax: $sql", errorCode = 400)

        val innerQuery = match.groupValues[1]

        return ExceptionMapper.executeWithExceptionHandling(connectionId) {
            // ExplainService를 통해 쿼리 실행 계획 생성
            val queryPlan = explainService.explain(innerQuery)

            // QueryPlan 객체를 JSON 문자열로 직렬화
            val queryPlanJson = objectMapper.writeValueAsString(queryPlan)

            DbResponse(
                success = true,
                message = "Query plan generated successfully",
                data = queryPlanJson
            )
        }
    }
}
