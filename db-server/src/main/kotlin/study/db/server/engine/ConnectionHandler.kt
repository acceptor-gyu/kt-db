package study.db.server.engine

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

class ConnectionHandler(
    private val socket: Socket
) : Runnable {

    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    private var state = ConnectionState.CONNECTED

    override fun run() {
        try {
            sendHandshake()
            state = ConnectionState.HANDSHAKE_SENT

            while (true) {
                val message = input.readUTF()
                handleMessage(message)
            }
        } catch (e: Exception) {
            socket.close()
        }
    }
}