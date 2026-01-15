package study.db.server.elasticsearch

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import org.springframework.data.elasticsearch.core.ElasticsearchOperations
import study.db.server.elasticsearch.document.IndexMetadata
import study.db.server.elasticsearch.document.QueryLog
import study.db.server.elasticsearch.document.TableMetadata

@SpringBootApplication(scanBasePackages = ["study.db.server.elasticsearch"])
class InitElasticsearchIndexApp {

    private val logger = LoggerFactory.getLogger(InitElasticsearchIndexApp::class.java)

    @Bean
    fun initIndex(elasticsearchOperations: ElasticsearchOperations): CommandLineRunner {
        return CommandLineRunner { args ->
            try {
                val forceMode = args.contains("--force")

                // Initialize QueryLog index
                logger.info("Initializing QueryLog index...")
                initializeIndex(elasticsearchOperations, QueryLog::class.java, "QueryLog", forceMode)

                // Initialize TableMetadata index
                logger.info("Initializing TableMetadata index...")
                initializeIndex(elasticsearchOperations, TableMetadata::class.java, "TableMetadata", forceMode)

                // Initialize IndexMetadata index
                logger.info("Initializing IndexMetadata index...")
                initializeIndex(elasticsearchOperations, IndexMetadata::class.java, "IndexMetadata", forceMode)

                logger.info("All indices initialized successfully")

            } catch (e: Exception) {
                logger.error("Failed to initialize Elasticsearch indices", e)
                throw e
            }
        }
    }

    private fun <T> initializeIndex(
        elasticsearchOperations: ElasticsearchOperations,
        documentClass: Class<T>,
        indexName: String,
        forceMode: Boolean
    ) {
        val indexOps = elasticsearchOperations.indexOps(documentClass)

        // Check if index exists
        if (indexOps.exists()) {
            logger.info("$indexName index already exists")

            // Check for --force flag
            if (forceMode) {
                logger.info("Deleting existing $indexName index")
                indexOps.delete()
                logger.info("$indexName index deleted")
            } else {
                logger.info("$indexName index already exists. Use --force to delete and recreate")
                return
            }
        }

        // Create index
        logger.info("Creating $indexName index")
        indexOps.create()

        // Create mapping
        indexOps.putMapping(indexOps.createMapping())

        logger.info("$indexName index created successfully")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(InitElasticsearchIndexApp::class.java, *args)
        }
    }
}
