package study.db.server.elasticsearch.example

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import study.db.server.elasticsearch.service.ExplainService

/**
 * EXPLAIN 명령 실행 예제
 *
 * 실행 방법:
 * 1. 샘플 데이터 초기화: ./gradlew :db-server:runInitSampleData
 * 2. EXPLAIN 예제 실행: ./gradlew :db-server:runExplainExample
 */
@SpringBootApplication(scanBasePackages = ["study.db.server.elasticsearch"])
class ExplainExampleApp {

    private val logger = LoggerFactory.getLogger(ExplainExampleApp::class.java)

    @Bean
    fun runExplainExamples(explainService: ExplainService): CommandLineRunner {
        return CommandLineRunner {
            logger.info("=".repeat(100))
            logger.info("EXPLAIN Command Examples")
            logger.info("=".repeat(100))
            logger.info("")

            val scenarios = listOf(
                Pair("INDEX_SCAN (Low Selectivity)", "SELECT * FROM users WHERE email = 'alice@example.com'"),
                Pair("TABLE_SCAN (High Selectivity)", "SELECT * FROM users WHERE age = 30"),
                Pair("COVERED_INDEX_SCAN", "SELECT name, email FROM users WHERE name = 'Alice'"),
                Pair("INDEX_SCAN (Not Covered)", "SELECT name, email, age FROM users WHERE name = 'Alice'"),
                Pair("Full TABLE_SCAN", "SELECT * FROM users"),
                Pair("INDEX_SCAN (Foreign Key)", "SELECT * FROM orders WHERE user_id = 12345"),
                Pair("TABLE_SCAN (Low Cardinality)", "SELECT * FROM orders WHERE status = 'PENDING'"),
                Pair("INDEX_SCAN (Category)", "SELECT * FROM products WHERE category = 'Electronics'")
            )

            scenarios.forEachIndexed { index, (title, sql) ->
                runScenario(explainService, index + 1, title, sql)
            }

            logger.info("")
            logger.info("=".repeat(100))
            logger.info("All EXPLAIN examples completed!")
            logger.info("=".repeat(100))
        }
    }

    private fun runScenario(explainService: ExplainService, number: Int, title: String, sql: String) {
        logger.info("─".repeat(100))
        logger.info("📊 Scenario $number: $title")
        logger.info("─".repeat(100))
        logger.info("SQL: $sql")
        logger.info("")

        try {
            val plan = explainService.explain(sql)

            logger.info("✅ Query Plan Generated:")
            logger.info("   Plan ID: ${plan.planId}")
            logger.info("   Query Hash: ${plan.queryHash}")
            logger.info("   Estimated Cost: ${plan.estimatedCost}")
            logger.info("   Estimated Rows: ${plan.estimatedRows}")
            logger.info("   Is Covered Query: ${plan.isCoveredQuery}")
            logger.info("")

            plan.executionSteps.forEach { step ->
                logger.info("   Step ${step.stepId}: ${step.stepType}")
                logger.info("      Table: ${step.tableName}")
                logger.info("      Index Used: ${step.indexUsed ?: "N/A"}")
                logger.info("      Filter: ${step.filterCondition ?: "N/A"}")
                logger.info("      Columns: ${step.columnsAccessed}")
                logger.info("      Cost: ${step.estimatedCost}")
                logger.info("      Rows: ${step.estimatedRows}")
                logger.info("      Is Covered: ${step.isCovered}")
                logger.info("      Description: ${step.description}")
            }

            logger.info("")

        } catch (e: Exception) {
            logger.error("❌ Failed: ${e.message}", e)
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(ExplainExampleApp::class.java, *args)
        }
    }
}
