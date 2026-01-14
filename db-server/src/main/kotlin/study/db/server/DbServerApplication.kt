package study.db.server

import java.util.Properties

fun main(args: Array<String>) {
    val port = loadPort()
    val tcpServer = DbTcpServer(port)

    Runtime.getRuntime().addShutdownHook(Thread {
        tcpServer.stop()
    })

    tcpServer.start()
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
