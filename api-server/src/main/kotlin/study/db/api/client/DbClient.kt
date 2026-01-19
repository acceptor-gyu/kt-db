package study.db.api.client

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import study.db.common.protocol.DbResponse
import study.db.common.protocol.ProtocolCodec
import java.net.Socket

@Component
class DbClient(
    @Value("\${db.server.host}") private val host: String,
    @Value("\${db.server.port}") private val port: Int
) {
    private val logger = LoggerFactory.getLogger(DbClient::class.java)

    @PostConstruct
    fun init() {
        logger.info("DbClient initialized with host={}, port={}", host, port)
    }

    fun send(sql: String): DbResponse {
        try {
            logger.info("Attempting to connect to {}:{} with SQL: {}", host, port, sql)
            return Socket(host, port).use { socket ->
                logger.info("Successfully connected to {}:{}", host, port)
                val requestBytes = ProtocolCodec.encodeRequest(sql)
                ProtocolCodec.writeMessage(socket.getOutputStream(), requestBytes)

                val responseBytes = ProtocolCodec.readMessage(socket.getInputStream())
                ProtocolCodec.decodeResponse(responseBytes)
            }
        } catch (e: Exception) {
            logger.error("Failed to send request to {}:{} - {}: {}", host, port, e.javaClass.simpleName, e.message, e)
            throw e
        }
    }
}
