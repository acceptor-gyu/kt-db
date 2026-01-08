package study.db.server

import study.db.server.tcp.DbTcpServer
import java.util.Properties

fun main(args: Array<String>) {
    val port = loadPort()
    val dbServer = DbServer()
    val server = DbTcpServer(port, dbServer)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
    })

    server.start()
}

private fun loadPort(): Int {
    val props = Properties()
    val inputStream = object {}.javaClass.getResourceAsStream("/application.properties")

    return if (inputStream != null) {
        props.load(inputStream)
        props.getProperty("db.server.port", "9000").toInt()
    } else {
        9000
    }
}
