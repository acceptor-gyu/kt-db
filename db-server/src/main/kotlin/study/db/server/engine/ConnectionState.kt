package study.db.server.engine

enum class ConnectionState {
    CONNECTED,
    HANDSHAKE_SENT,
    AUTHENTICATED,
    COMMAND
}