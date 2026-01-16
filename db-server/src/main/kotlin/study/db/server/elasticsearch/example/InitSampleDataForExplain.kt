package study.db.server.elasticsearch.example

import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Bean
import study.db.server.elasticsearch.document.*
import study.db.server.elasticsearch.repository.TableStatisticsRepository
import study.db.server.elasticsearch.service.IndexMetadataService
import study.db.server.elasticsearch.service.TableMetadataService
import study.db.server.elasticsearch.service.TableStatisticsService
import java.time.Instant

/**
 * EXPLAIN 기능 테스트를 위한 샘플 데이터 초기화
 *
 * 실행 방법:
 * ```
 * ./gradlew :db-server:runInitSampleData
 * ```
 */
@SpringBootApplication(scanBasePackages = ["study.db.server.elasticsearch"])
class InitSampleDataForExplainApp {

    private val logger = LoggerFactory.getLogger(InitSampleDataForExplainApp::class.java)

    @Bean
    fun initSampleData(
        tableMetadataService: TableMetadataService,
        indexMetadataService: IndexMetadataService,
        tableStatisticsService: TableStatisticsService,
        tableStatisticsRepository: TableStatisticsRepository
    ): CommandLineRunner {
        return CommandLineRunner {
            logger.info("=".repeat(80))
            logger.info("Initializing sample data for EXPLAIN testing...")
            logger.info("=".repeat(80))
            logger.info("")

            initUsersTable(tableMetadataService, indexMetadataService, tableStatisticsService, tableStatisticsRepository)
            initOrdersTable(tableMetadataService, indexMetadataService, tableStatisticsService, tableStatisticsRepository)
            initProductsTable(tableMetadataService, indexMetadataService, tableStatisticsService, tableStatisticsRepository)

            logger.info("")
            logger.info("=".repeat(80))
            logger.info("Sample data initialization completed!")
            logger.info("=".repeat(80))
            printTestScenarios()
        }
    }

    private fun initUsersTable(
        tableMetadataService: TableMetadataService,
        indexMetadataService: IndexMetadataService,
        tableStatisticsService: TableStatisticsService,
        tableStatisticsRepository: TableStatisticsRepository
    ) {
        logger.info("\n[1/3] Initializing users table...")

        val tableName = "users"
        val columns = listOf(
            ColumnInfo("id", "INT"),
            ColumnInfo("name", "VARCHAR"),
            ColumnInfo("email", "VARCHAR"),
            ColumnInfo("age", "INT"),
            ColumnInfo("city", "VARCHAR")
        )

        tableMetadataService.createTable(tableName, columns)
        tableMetadataService.updateRowCount(tableName, 100_000)
        logger.info("✓ Created table '$tableName' with 100,000 rows")

        indexMetadataService.createIndex("idx_email", tableName, listOf("email"), unique = false)
        logger.info("✓ Created index 'idx_email' on (email)")

        indexMetadataService.createIndex("idx_age", tableName, listOf("age"), unique = false)
        logger.info("✓ Created index 'idx_age' on (age)")

        indexMetadataService.createIndex("idx_name_email", tableName, listOf("name", "email"), unique = false)
        logger.info("✓ Created index 'idx_name_email' on (name, email)")

        // 통계 생성
        val stats = TableStatistics(
            statsId = tableName,
            tableName = tableName,
            totalRows = 100_000,
            columnStats = listOf(
                ColumnStatistics("id", distinctCount = 100_000, nullCount = 0),
                ColumnStatistics("name", distinctCount = 50_000, nullCount = 0),
                ColumnStatistics("email", distinctCount = 99_500, nullCount = 0),
                ColumnStatistics("age", distinctCount = 60, nullCount = 100),
                ColumnStatistics("city", distinctCount = 50, nullCount = 0)
            ),
            updatedAt = Instant.now()
        )
        tableStatisticsRepository.save(stats)
        logger.info("✓ Created statistics for '$tableName'")
    }

    private fun initOrdersTable(
        tableMetadataService: TableMetadataService,
        indexMetadataService: IndexMetadataService,
        tableStatisticsService: TableStatisticsService,
        tableStatisticsRepository: TableStatisticsRepository
    ) {
        logger.info("\n[2/3] Initializing orders table...")

        val tableName = "orders"
        val columns = listOf(
            ColumnInfo("id", "INT"),
            ColumnInfo("user_id", "INT"),
            ColumnInfo("product_id", "INT"),
            ColumnInfo("quantity", "INT"),
            ColumnInfo("status", "VARCHAR"),
            ColumnInfo("created_at", "TIMESTAMP")
        )

        tableMetadataService.createTable(tableName, columns)
        tableMetadataService.updateRowCount(tableName, 500_000)
        logger.info("✓ Created table '$tableName' with 500,000 rows")

        indexMetadataService.createIndex("idx_user_id", tableName, listOf("user_id"), unique = false)
        logger.info("✓ Created index 'idx_user_id' on (user_id)")

        indexMetadataService.createIndex("idx_status", tableName, listOf("status"), unique = false)
        logger.info("✓ Created index 'idx_status' on (status)")

        indexMetadataService.createIndex("idx_created_at", tableName, listOf("created_at"), unique = false)
        logger.info("✓ Created index 'idx_created_at' on (created_at)")

        val stats = TableStatistics(
            statsId = tableName,
            tableName = tableName,
            totalRows = 500_000,
            columnStats = listOf(
                ColumnStatistics("id", distinctCount = 500_000, nullCount = 0),
                ColumnStatistics("user_id", distinctCount = 100_000, nullCount = 0),
                ColumnStatistics("product_id", distinctCount = 10_000, nullCount = 0),
                ColumnStatistics("status", distinctCount = 5, nullCount = 0),
                ColumnStatistics("created_at", distinctCount = 365_000, nullCount = 0)
            ),
            updatedAt = Instant.now()
        )
        tableStatisticsRepository.save(stats)
        logger.info("✓ Created statistics for '$tableName'")
    }

    private fun initProductsTable(
        tableMetadataService: TableMetadataService,
        indexMetadataService: IndexMetadataService,
        tableStatisticsService: TableStatisticsService,
        tableStatisticsRepository: TableStatisticsRepository
    ) {
        logger.info("\n[3/3] Initializing products table...")

        val tableName = "products"
        val columns = listOf(
            ColumnInfo("id", "INT"),
            ColumnInfo("name", "VARCHAR"),
            ColumnInfo("category", "VARCHAR"),
            ColumnInfo("price", "DECIMAL"),
            ColumnInfo("stock", "INT")
        )

        tableMetadataService.createTable(tableName, columns)
        tableMetadataService.updateRowCount(tableName, 10_000)
        logger.info("✓ Created table '$tableName' with 10,000 rows")

        indexMetadataService.createIndex("idx_category", tableName, listOf("category"), unique = false)
        logger.info("✓ Created index 'idx_category' on (category)")

        indexMetadataService.createIndex("idx_price", tableName, listOf("price"), unique = false)
        logger.info("✓ Created index 'idx_price' on (price)")

        val stats = TableStatistics(
            statsId = tableName,
            tableName = tableName,
            totalRows = 10_000,
            columnStats = listOf(
                ColumnStatistics("id", distinctCount = 10_000, nullCount = 0),
                ColumnStatistics("name", distinctCount = 9_500, nullCount = 0),
                ColumnStatistics("category", distinctCount = 20, nullCount = 0),
                ColumnStatistics("price", distinctCount = 5_000, nullCount = 0),
                ColumnStatistics("stock", distinctCount = 1_000, nullCount = 50)
            ),
            updatedAt = Instant.now()
        )
        tableStatisticsRepository.save(stats)
        logger.info("✓ Created statistics for '$tableName'")
    }

    private fun printTestScenarios() {
        logger.info("")
        logger.info("📋 EXPLAIN Test Scenarios:")
        logger.info("")
        logger.info("=".repeat(80))
        logger.info("1️⃣  INDEX_SCAN: SELECT * FROM users WHERE email = 'alice@example.com'")
        logger.info("=".repeat(80))
        logger.info("2️⃣  TABLE_SCAN: SELECT * FROM users WHERE age = 30")
        logger.info("=".repeat(80))
        logger.info("3️⃣  COVERED_INDEX_SCAN: SELECT name, email FROM users WHERE name = 'Alice'")
        logger.info("=".repeat(80))
        logger.info("4️⃣  INDEX_SCAN (Not Covered): SELECT name, email, age FROM users WHERE name = 'Alice'")
        logger.info("=".repeat(80))
        logger.info("5️⃣  Full TABLE_SCAN: SELECT * FROM users")
        logger.info("=".repeat(80))
        logger.info("6️⃣  INDEX_SCAN (FK): SELECT * FROM orders WHERE user_id = 12345")
        logger.info("=".repeat(80))
        logger.info("7️⃣  TABLE_SCAN (Low Cardinality): SELECT * FROM orders WHERE status = 'PENDING'")
        logger.info("=".repeat(80))
        logger.info("8️⃣  INDEX_SCAN (Category): SELECT * FROM products WHERE category = 'Electronics'")
        logger.info("=".repeat(80))
        logger.info("")
        logger.info("🚀 Ready to test! Run: ./gradlew :db-server:runExplainExample")
        logger.info("")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(InitSampleDataForExplainApp::class.java, *args)
        }
    }
}
