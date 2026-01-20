package study.db.server

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import study.db.server.elasticsearch.service.ExplainService
import study.db.server.service.TableService
import study.db.server.storage.*
import java.io.File

/**
 * DbServerApplication - Spring Boot 기반 DB 서버
 *
 * TCP 서버를 Spring 컨텍스트 안에서 실행하여:
 * - ExplainService 같은 Spring Bean을 ConnectionHandler에서 사용 가능
 * - Elasticsearch 연동 기능 활용
 */
@SpringBootApplication(scanBasePackages = ["study.db.server"])
class DbServerApplication {

    /**
     * RowEncoder 빈 생성
     * 모든 필드 인코더를 포함한 RowEncoder 생성
     */
    @Bean
    fun rowEncoder(): RowEncoder {
        return RowEncoder(
            IntFieldEncoder(),
            VarcharFieldEncoder(),
            BooleanFieldEncoder(),
            TimestampFieldEncoder()
        )
    }

    /**
     * TableFileManager 빈 생성
     * application.properties의 db.storage.directory 설정 사용
     */
    @Bean
    fun tableFileManager(
        @Value("\${db.storage.directory:./data}") storageDirectory: String,
        rowEncoder: RowEncoder
    ): TableFileManager {
        val dataDir = File(storageDirectory)
        return TableFileManager(dataDir, rowEncoder)
    }

    /**
     * TableService 빈 생성
     * TableFileManager를 사용하여 파일 기반 persistence 지원
     */
    @Bean
    fun tableService(tableFileManager: TableFileManager): TableService {
        return TableService(tableFileManager)
    }

    @Bean
    fun tcpServerRunner(
        explainService: ExplainService,
        tableService: TableService,
        @Value("\${db.server.port:9000}") port: Int
    ): CommandLineRunner {
        return CommandLineRunner {
            val tcpServer = DbTcpServer(
                port = port,
                explainService = explainService,
                tableService = tableService
            )

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
