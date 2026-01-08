package study.db.server.tcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest
import study.db.common.protocol.DbResponse
import study.db.common.protocol.ProtocolCodec
import study.db.server.service.TableService
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class DbTcpServer(
    private val port: Int,
    private val tableService: TableService = TableService()
) {
    private val executor = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var running = false

    private val json = Json { encodeDefaults = true }

    fun start() {
        serverSocket = ServerSocket(port)
        running = true

        println("DB Server started on port $port")

        while (running) {
            try {
                // 여기서 TCP handshake 포함
                val clientSocket = serverSocket?.accept() ?: break
                executor.submit { handleClient(clientSocket) }
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
        executor.shutdown()
        println("DB Server stopped")
    }

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
