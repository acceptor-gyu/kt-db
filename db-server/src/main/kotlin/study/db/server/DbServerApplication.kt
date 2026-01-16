package study.db.server

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import study.db.server.elasticsearch.service.ExplainService

/**
 * DbServerApplication - Spring Boot 기반 DB 서버
 *
 * TCP 서버를 Spring 컨텍스트 안에서 실행하여:
 * - ExplainService 같은 Spring Bean을 ConnectionHandler에서 사용 가능
 * - Elasticsearch 연동 기능 활용
 */
@SpringBootApplication(scanBasePackages = ["study.db.server"])
class DbServerApplication {

    @Bean
    fun tcpServerRunner(explainService: ExplainService): CommandLineRunner {
        return CommandLineRunner {
            val port = 9000 // TODO: application.properties에서 읽기
            val tcpServer = DbTcpServer(port, explainService = explainService)

            Runtime.getRuntime().addShutdownHook(Thread {
                tcpServer.stop()
            })

            // TCP 서버를 별도 스레드에서 실행 (non-blocking)
            Thread {
                tcpServer.start()
            }.start()
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(DbServerApplication::class.java, *args)
        }
    }
}
