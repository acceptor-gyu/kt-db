package study.db.server.tcp

import study.db.server.DbServer
import study.db.server.engine.ConnectionHandler
import java.net.ServerSocket

class DbTcpServer(
    private val port: Int,
    private val dbServer: DbServer = DbServer()
) {

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    fun start() {
        serverSocket = ServerSocket(port)
        running = true

        println("DB Server started on port $port")

        while (running) {
            try {
                // 여기서 TCP handshake 포함
                val clientSocket = serverSocket?.accept() ?: break
                ConnectionHandler(clientSocket, dbServer.executor)
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
        println("DB Server stopped")
    }
}
