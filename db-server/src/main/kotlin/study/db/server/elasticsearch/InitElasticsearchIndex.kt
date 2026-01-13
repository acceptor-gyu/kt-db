package study.db.server.elasticsearch

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import study.db.server.elasticsearch.document.QueryLog

@SpringBootApplication(scanBasePackages = ["study.db.server.elasticsearch"])
class InitElasticsearchIndexApp {

    private val logger = LoggerFactory.getLogger(InitElasticsearchIndexApp::class.java)

    @Bean
    fun initIndex(elasticsearchOperations: ElasticsearchOperations): CommandLineRunner {
        return CommandLineRunner { args ->
            try {
                val indexOps = elasticsearchOperations.indexOps(QueryLog::class.java)

                // Check if index exists
                if (indexOps.exists()) {
                    logger.info("Index already exists")

                    // Check for --force flag
                    if (args.contains("--force")) {
                        logger.info("Deleting existing index")
                        indexOps.delete()
                        logger.info("Index deleted")
                    } else {
                        logger.info("Use --force to delete and recreate the index")
                        return@CommandLineRunner
                    }
                }

                // Create index
                logger.info("Creating index")
                indexOps.create()

                // Create mapping
                indexOps.putMapping(indexOps.createMapping())

                logger.info("Index created successfully")

            } catch (e: Exception) {
                logger.error("Failed to initialize Elasticsearch index", e)
                throw e
            }
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(InitElasticsearchIndexApp::class.java, *args)
        }
    }
}
