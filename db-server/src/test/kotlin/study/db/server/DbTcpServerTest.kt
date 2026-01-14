package study.db.server

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlin.concurrent.thread

/**
 * DbTcpServer 테스트
 *
 * 테스트 시나리오:
 * 1. 정상 연결 및 종료
 * 2. 최대 연결 수 초과 시 거부 (Connection Pool Exhaustion)
 * 3. 동시 다중 연결 처리
 */
class DbTcpServerTest {

    @Test
    @DisplayName("정상 연결 및 종료 테스트")
    fun `should accept and close connection successfully`() {
        val port = 25432
        val server = DbTcpServer(port = port, maxConnections = 5)
        val serverThread = thread(name = "test-server") {
            server.start()
        }

        try {
            Thread.sleep(500) // 서버 시작 대기

            val socket = Socket("localhost", port)
            assertTrue(socket.isConnected, "클라이언트가 서버에 연결되어야 함")

            socket.close()
            Thread.sleep(200) // 연결 정리 대기

            assertEquals(0, server.getActiveConnectionCount(), "연결 종료 후 활성 연결 수는 0이어야 함")
        } finally {
            server.stop()
            serverThread.join(5000)
        }
    }

    @Test
    @DisplayName("최대 연결 수 초과 시 거부 테스트")
    fun `should reject connections when max connections reached`() {
        val port = 25433
        val maxConnections = 3
        val server = DbTcpServer(port = port, maxConnections = maxConnections)
        val serverThread = thread(name = "test-server") {
            server.start()
        }

        try {
            Thread.sleep(500) // 서버 시작 대기

            val sockets = mutableListOf<Socket>()
            val readers = mutableListOf<BufferedReader>()

            try {
                // maxConnections 만큼 연결 생성
                repeat(maxConnections) { i ->
                    val socket = Socket("localhost", port)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    sockets.add(socket)
                    readers.add(reader)

                    // 핸드셰이크 읽기
                    val handshake = reader.readLine()
                    assertTrue(handshake != null, "핸드셰이크를 받아야 함 (연결 ${i + 1})")
                    assertTrue(socket.isConnected, "연결 ${i + 1}이 성공해야 함")
                }

                Thread.sleep(500) // 연결 등록 대기
                assertEquals(maxConnections, server.getActiveConnectionCount(),
                    "활성 연결 수가 최대 연결 수와 같아야 함")

                // 추가 연결 시도 - 거부되어야 함
                val rejectedSocket = Socket("localhost", port)
                val rejectedReader = BufferedReader(InputStreamReader(rejectedSocket.getInputStream()))

                // 에러 메시지 읽기
                val errorMessage = rejectedReader.readLine()
                assertTrue(errorMessage != null, "에러 메시지를 받아야 함")
                assertTrue(errorMessage.contains("Too many connections"),
                    "에러 메시지에 'Too many connections'가 포함되어야 함: $errorMessage")

                rejectedSocket.close()

                // 활성 연결 수는 여전히 maxConnections여야 함
                assertEquals(maxConnections, server.getActiveConnectionCount(),
                    "거부된 연결은 활성 연결 수에 포함되지 않아야 함")

            } finally {
                // 모든 소켓 정리
                readers.forEach { try { it.close() } catch (e: Exception) {} }
                sockets.forEach { try { it.close() } catch (e: Exception) {} }
            }

            Thread.sleep(500) // 연결 정리 대기
            assertEquals(0, server.getActiveConnectionCount(),
                "모든 연결 종료 후 활성 연결 수는 0이어야 함")

        } finally {
            server.stop()
            serverThread.join(5000)
        }
    }

    @Test
    @DisplayName("동시 다중 연결 처리 테스트")
    fun `should handle multiple concurrent connections`() {
        val port = 25434
        val maxConnections = 5
        val server = DbTcpServer(port = port, maxConnections = maxConnections)
        val serverThread = thread(name = "test-server") {
            server.start()
        }

        try {
            Thread.sleep(500) // 서버 시작 대기

            val sockets = mutableListOf<Socket>()

            try {
                // 순차적으로 연결 생성
                repeat(maxConnections) { i ->
                    val socket = Socket("localhost", port)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    // 핸드셰이크 읽기
                    val handshake = reader.readLine()
                    assertTrue(handshake != null && handshake.startsWith("HANDSHAKE"),
                        "연결 ${i + 1}이 핸드셰이크를 받아야 함")

                    sockets.add(socket)
                    Thread.sleep(100)
                }

                Thread.sleep(500) // 연결 등록 대기
                assertEquals(maxConnections, server.getActiveConnectionCount(),
                    "모든 연결이 활성화되어야 함")

                // 모든 소켓 닫기
                sockets.forEach { it.close() }
                sockets.clear()

                Thread.sleep(500) // 연결 정리 대기

                // 모든 연결이 정리되어야 함
                assertEquals(0, server.getActiveConnectionCount(),
                    "모든 연결이 정리되어야 함")
            } finally {
                sockets.forEach {
                    try {
                        it.close()
                    } catch (e: Exception) {
                        // 이미 닫혔을 수 있음
                    }
                }
            }
        } finally {
            server.stop()
            serverThread.join(5000)
        }
    }
}
