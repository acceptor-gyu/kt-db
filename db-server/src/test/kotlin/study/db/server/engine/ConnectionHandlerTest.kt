package study.db.server.engine

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest
import study.db.common.protocol.ProtocolCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ConnectionHandler 테스트
 *
 * ConnectionHandler는 클라이언트와의 연결을 관리하고
 * 핸드셰이크, 인증, 명령 처리 등의 역할을 수행합니다.
 */
@DisplayName("ConnectionHandler 테스트")
class ConnectionHandlerTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var clientSocket: Socket
    private lateinit var serverSideSocket: Socket
    private val executor = Executors.newFixedThreadPool(2)

    /**
     * 각 테스트 전에 서버와 클라이언트 소켓을 생성합니다.
     */
    @BeforeEach
    fun setup() {
        // 테스트용 서버 소켓 생성 (포트 0 = 자동 할당)
        serverSocket = ServerSocket(0)

        // 클라이언트 소켓을 별도 스레드에서 연결
        executor.submit {
            serverSideSocket = serverSocket.accept()
        }

        // 클라이언트 측에서 연결
        clientSocket = Socket("localhost", serverSocket.localPort)

        // 서버 측 소켓 연결 대기
        Thread.sleep(100)
    }

    /**
     * 각 테스트 후 리소스를 정리합니다.
     */
    @AfterEach
    fun cleanup() {
        try {
            clientSocket.close()
        } catch (e: Exception) {
            // 이미 닫혔을 수 있음
        }
        try {
            if (::serverSideSocket.isInitialized) {
                serverSideSocket.close()
            }
        } catch (e: Exception) {
            // 이미 닫혔을 수 있음
        }
        try {
            serverSocket.close()
        } catch (e: Exception) {
            // 이미 닫혔을 수 있음
        }
        executor.shutdown()
        executor.awaitTermination(1, TimeUnit.SECONDS)
    }

    @Nested
    @DisplayName("핸드셰이크 테스트")
    inner class HandshakeTest {

        @Test
        @DisplayName("핸드셰이크 메시지 전송 확인")
        fun `sends handshake message on connection`() {
            // Given: ConnectionHandler 생성
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())

            // When: 별도 스레드에서 핸들러 실행
            executor.submit(handler)

            // Then: 클라이언트가 핸드셰이크 메시지를 수신
            val input = DataInputStream(clientSocket.getInputStream())
            val handshake = input.readUTF()

            assertTrue(handshake.startsWith("HANDSHAKE"))
            assertTrue(handshake.contains("v1.0"))
        }

        @Test
        @DisplayName("핸드셰이크 후 인증 대기 상태로 전환")
        fun `transitions to handshake sent state after handshake`() {
            // Given: ConnectionHandler 생성 및 실행
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            // When: 핸드셰이크 메시지 수신
            val input = DataInputStream(clientSocket.getInputStream())
            val handshake = input.readUTF()

            // Then: 핸드셰이크 메시지가 정상적으로 수신됨
            assertNotNull(handshake)
            assertTrue(handshake.startsWith("HANDSHAKE"))
        }
    }

    @Nested
    @DisplayName("인증 테스트")
    inner class AuthenticationTest {

        @Test
        @DisplayName("올바른 인증 정보로 인증 성공")
        fun `authenticates with valid credentials`() {
            // Given: ConnectionHandler 생성 및 실행
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            // When: 핸드셰이크 수신 후 인증 메시지 전송
            input.readUTF() // 핸드셰이크 무시
            // TODO: application.properties의 실제 인증 정보 사용 (user/9000)
            output.writeUTF("AUTH|user|9000")
            output.flush()

            // Then: 인증 성공 메시지 수신
            val response = input.readUTF()
            assertEquals("Authenticated", response)
        }

        @Test
        @DisplayName("잘못된 인증 정보로 인증 실패")
        fun `fails authentication with invalid credentials`() {
            // Given: ConnectionHandler 생성 및 실행
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            // When: 핸드셰이크 수신 후 잘못된 인증 정보 전송
            input.readUTF() // 핸드셰이크 무시
            output.writeUTF("AUTH|wronguser|wrongpass")
            output.flush()

            // Then: 에러 메시지 수신 및 연결 종료
            val response = input.readUTF()
            assertEquals("ER_NOT_SUPPORTED_AUTH_MODE", response)
        }

        @Test
        @DisplayName("AUTH 형식이 아닌 메시지로 프로토콜 에러")
        fun `fails with non-auth message during auth phase`() {
            // Given: ConnectionHandler 생성 및 실행
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            // When: 핸드셰이크 수신 후 잘못된 형식의 메시지 전송
            input.readUTF() // 핸드셰이크 무시
            output.writeUTF("INVALID_MESSAGE")
            output.flush()

            // Then: 프로토콜 에러 메시지 수신
            val response = input.readUTF()
            assertEquals("ER_NOT_SUPPORTED_AUTH_MODE", response)
        }
    }

    @Nested
    @DisplayName("명령 처리 테스트")
    inner class CommandHandlingTest {

        /**
         * 인증을 완료한 상태의 클라이언트 소켓을 반환합니다.
         * TODO: 실제 애플리케이션 설정에서 인증 정보를 읽어오도록 개선
         */
        private fun getAuthenticatedClient(): Pair<DataInputStream, DataOutputStream> {
            val input = DataInputStream(clientSocket.getInputStream())
            val output = DataOutputStream(clientSocket.getOutputStream())

            // 핸드셰이크
            input.readUTF()

            // 인증 (application.properties: user/9000)
            output.writeUTF("AUTH|user|9000")
            output.flush()
            input.readUTF() // "Authenticated" 응답 읽기

            return Pair(input, output)
        }

        @Test
        @DisplayName("인증 후 명령 수신 가능")
        fun `can receive commands after authentication`() {
            // Given: 인증된 ConnectionHandler
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            val (input, output) = getAuthenticatedClient()

            // When: 명령 전송
            output.writeUTF("SELECT * FROM users")
            output.flush()

            // Then: 명령 처리 응답 수신
            val response = input.readUTF()
            assertTrue(response.contains("Command received"))
        }

        @Test
        @DisplayName("여러 명령을 순차적으로 처리")
        fun `can handle multiple commands sequentially`() {
            // Given: 인증된 ConnectionHandler
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            val (input, output) = getAuthenticatedClient()

            // When: 여러 명령 순차 전송
            val commands = listOf(
                "CREATE TABLE users",
                "INSERT INTO users VALUES",
                "SELECT * FROM users"
            )

            // Then: 각 명령에 대한 응답 수신
            commands.forEach { command ->
                output.writeUTF(command)
                output.flush()

                val response = input.readUTF()
                assertTrue(response.contains("Command received"))
                assertTrue(response.contains(command))
            }
        }
    }

    @Nested
    @DisplayName("프로토콜 처리 테스트")
    inner class ProtocolTest {

        @Test
        @DisplayName("ProtocolCodec을 통한 요청 처리 구조 확인")
        fun `protocol codec request structure is available`() {
            // Given: DbRequest 생성
            val request = DbRequest(
                command = DbCommand.PING,
                tableName = null,
                columns = null,
                values = null
            )

            // When: 요청 인코딩
            val encoded = ProtocolCodec.encodeRequest(request)

            // Then: 정상적으로 인코딩됨
            assertNotNull(encoded)
            assertTrue(encoded.isNotEmpty())
        }

        @Test
        @DisplayName("CREATE_TABLE 명령 요청 생성")
        fun `can create CREATE_TABLE request`() {
            // Given: CREATE TABLE 요청
            val request = DbRequest(
                command = DbCommand.CREATE_TABLE,
                tableName = "users",
                columns = mapOf("id" to "INT", "name" to "VARCHAR"),
                values = null
            )

            // When: 요청 인코딩 및 디코딩
            val encoded = ProtocolCodec.encodeRequest(request)
            val decoded = ProtocolCodec.decodeRequest(encoded)

            // Then: 요청이 정상적으로 복원됨
            assertEquals(DbCommand.CREATE_TABLE, decoded.command)
            assertEquals("users", decoded.tableName)
            assertNotNull(decoded.columns)
        }

        @Test
        @DisplayName("INSERT 명령 요청 생성")
        fun `can create INSERT request`() {
            // Given: INSERT 요청
            val request = DbRequest(
                command = DbCommand.INSERT,
                tableName = "users",
                columns = null,
                values = mapOf("id" to "1", "name" to "John")
            )

            // When: 요청 인코딩 및 디코딩
            val encoded = ProtocolCodec.encodeRequest(request)
            val decoded = ProtocolCodec.decodeRequest(encoded)

            // Then: 요청이 정상적으로 복원됨
            assertEquals(DbCommand.INSERT, decoded.command)
            assertEquals("users", decoded.tableName)
            assertNotNull(decoded.values)
        }
    }

    @Nested
    @DisplayName("에러 처리 테스트")
    inner class ErrorHandlingTest {

        @Test
        @DisplayName("소켓 종료 시 예외 처리")
        fun `handles socket closure gracefully`() {
            // Given: ConnectionHandler 생성
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())

            // When: 핸들러 실행 후 클라이언트 소켓 즉시 종료
            executor.submit(handler)
            Thread.sleep(50) // 핸드셰이크 완료 대기
            clientSocket.close()

            // Then: 예외 없이 정상 종료됨 (실제로는 핸들러 내부에서 처리)
            Thread.sleep(100)
            // 예외가 발생하지 않으면 성공
        }

        @Test
        @DisplayName("연결 끊김 시 리소스 정리")
        fun `cleans up resources on disconnection`() {
            // Given: ConnectionHandler 생성 및 실행
            val handler = ConnectionHandler(serverSideSocket, Executors.newSingleThreadExecutor())
            executor.submit(handler)

            // When: 연결 끊김
            Thread.sleep(50)

            // Then: 소켓이 아직 연결되어 있음
            assertFalse(serverSideSocket.isClosed)

            // 클라이언트 소켓 종료
            clientSocket.close()
            Thread.sleep(100)

            // 핸들러가 종료 처리를 수행함
        }
    }
}
