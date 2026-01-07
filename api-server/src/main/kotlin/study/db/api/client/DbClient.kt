package study.db.api.client

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import study.db.common.protocol.DbRequest
import study.db.common.protocol.DbResponse
import study.db.common.protocol.ProtocolCodec
import java.net.Socket

@Component
class DbClient(
    @Value("\${db.server.host}") private val host: String,
    @Value("\${db.server.port}") private val port: Int
) {
    fun send(request: DbRequest): DbResponse {
        return Socket(host, port).use { socket ->
            val requestBytes = ProtocolCodec.encodeRequest(request)
            ProtocolCodec.writeMessage(socket.getOutputStream(), requestBytes)

            val responseBytes = ProtocolCodec.readMessage(socket.getInputStream())
            ProtocolCodec.decodeResponse(responseBytes)
        }
    }
}
