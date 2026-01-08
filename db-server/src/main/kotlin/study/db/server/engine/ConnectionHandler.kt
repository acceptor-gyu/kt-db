package study.db.server.engine

import kotlinx.serialization.json.Json
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
import java.util.concurrent.ExecutorService

class ConnectionHandler(
    private val socket: Socket,
    private val executor: ExecutorService

) : Runnable {
    val properties = Properties()
    private val tableService: TableService = TableService()
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

    override fun run() {
        try {
            // application level handshake
            sendHandshake()
            state = ConnectionState.HANDSHAKE_SENT

            while (true) {
                val message = input.readUTF()
                handleMessage(message)
            }
        } catch (e: Exception) {
            executor.shutdown()
            socket.close()
        }
    }

    private fun handleMessage(message: String) {
        when (state) {
            ConnectionState.HANDSHAKE_SENT -> handleAuth(message)
            ConnectionState.AUTHENTICATED -> handleCommand(message)
            else -> protocolError()
        }
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
     * 추후 Parser쪽으로 역할 분리
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

    private fun handleSelect(request: DbRequest): DbResponse {
        val tableName = request.tableName
            ?: return DbResponse(success = false, message = "Table name is required", errorCode = 400)

        val table = tableService.select(tableName)
        return if (table != null) {
            DbResponse(success = true, data = json.encodeToString(table))
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