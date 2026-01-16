package study.db.server.db_engine

enum class ConnectionState {
    CONNECTED,
    HANDSHAKE_SENT,
    AUTHENTICATED,
    COMMAND
}