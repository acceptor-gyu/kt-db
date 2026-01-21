package study.db.server.storage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import study.db.common.Table
import java.io.File

@DisplayName("BufferPool Integration Test - TableFileManager와 통합")
class BufferPoolIntegrationTest {

    private lateinit var tempDir: File
    private lateinit var bufferPool: BufferPool
    private lateinit var tableFileManager: TableFileManager
    private lateinit var rowEncoder: RowEncoder

    @BeforeEach
    fun setUp() {
        tempDir = createTempDir("buffer_pool_integration_test")
        bufferPool = BufferPool(maxPages = 10)
        rowEncoder = RowEncoder(
            IntFieldEncoder(),
            VarcharFieldEncoder(),
            BooleanFieldEncoder(),
            TimestampFieldEncoder()
        )
        tableFileManager = TableFileManager(tempDir, rowEncoder, bufferPool)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("BufferPool 없이 readTable - 직접 디스크 읽기")
    fun `reads table without buffer pool`() {
        val managerWithoutCache = TableFileManager(tempDir, rowEncoder, bufferPool = null)

        val table = Table(
            tableName = "users",
            dataType = mapOf("id" to "INT", "name" to "VARCHAR"),
            rows = (1..10).map { mapOf("id" to it.toString(), "name" to "User$it") }
        )

        managerWithoutCache.writeTable(table)
        val loaded = managerWithoutCache.readTable("users")

        assertNotNull(loaded)
        assertEquals(10, loaded?.rows?.size)
    }

    @Test
    @DisplayName("BufferPool 있이 readTable - 캐싱 사용")
    fun `reads table with buffer pool caching`() {
        val table = Table(
            tableName = "users",
            dataType = mapOf("id" to "INT", "name" to "VARCHAR"),
            rows = (1..50).map { mapOf("id" to it.toString(), "name" to "User$it") }
        )

        tableFileManager.writeTable(table)

        // First read - cache miss
        val loaded1 = tableFileManager.readTable("users")
        assertNotNull(loaded1)
        assertEquals(50, loaded1?.rows?.size)

        val statsAfterFirstRead = bufferPool.getStats()
        assertTrue(statsAfterFirstRead.totalPages > 0, "Should have cached pages")

        // Second read - cache hit
        val loaded2 = tableFileManager.readTable("users")
        assertNotNull(loaded2)
        assertEquals(50, loaded2?.rows?.size)

        val statsAfterSecondRead = bufferPool.getStats()
        assertTrue(statsAfterSecondRead.hitCount > 0, "Should have cache hits")
        assertTrue(statsAfterSecondRead.hitRate > 0, "Hit rate should be greater than 0")
    }

    @Test
    @DisplayName("여러 테이블 읽기 - BufferPool 캐싱")
    fun `caches multiple tables`() {
        val table1 = Table("table1", mapOf("id" to "INT"), (1..20).map { mapOf("id" to it.toString()) })
        val table2 = Table("table2", mapOf("name" to "VARCHAR"), (1..20).map { mapOf("name" to "User$it") })

        tableFileManager.writeTable(table1)
        tableFileManager.writeTable(table2)

        tableFileManager.readTable("table1")
        tableFileManager.readTable("table2")

        val stats = bufferPool.getStats()
        assertTrue(stats.totalPages > 0, "Should cache pages from both tables")
    }

    @Test
    @DisplayName("deleteTable - BufferPool invalidation")
    fun `invalidates buffer pool on table deletion`() {
        val table = Table(
            tableName = "to_delete",
            dataType = mapOf("id" to "INT"),
            rows = (1..30).map { mapOf("id" to it.toString()) }
        )

        tableFileManager.writeTable(table)
        tableFileManager.readTable("to_delete")

        val statsBefore = bufferPool.getStats()
        assertTrue(statsBefore.totalPages > 0, "Should have cached pages")

        tableFileManager.deleteTable("to_delete")

        val statsAfter = bufferPool.getStats()
        assertEquals(0, statsAfter.totalPages, "All pages should be invalidated after deletion")
    }

    @Test
    @DisplayName("반복 읽기 - 높은 hit rate")
    fun `achieves high hit rate with repeated reads`() {
        val table = Table(
            tableName = "cached_table",
            dataType = mapOf("id" to "INT", "value" to "VARCHAR"),
            rows = (1..100).map { mapOf("id" to it.toString(), "value" to "Data$it") }
        )

        tableFileManager.writeTable(table)

        // Read 10 times
        repeat(10) {
            tableFileManager.readTable("cached_table")
        }

        val stats = bufferPool.getStats()
        assertTrue(stats.hitRate > 80.0, "Hit rate should be > 80% after repeated reads (actual: ${stats.hitRate}%)")
        println("Hit rate after 10 reads: ${stats.hitRate}%")
    }

    @Test
    @DisplayName("페이지 단위 읽기 - BufferPool 캐싱")
    fun `caches individual pages`() {
        val table = Table(
            tableName = "page_test",
            dataType = mapOf("id" to "INT"),
            rows = (1..100).map { mapOf("id" to it.toString()) }
        )

        tableFileManager.writeTable(table)

        // Read first page twice
        val page1 = tableFileManager.readPage("page_test", 0)
        val page2 = tableFileManager.readPage("page_test", 0)

        assertNotNull(page1)
        assertNotNull(page2)

        val stats = bufferPool.getStats()
        assertEquals(1, stats.missCount, "Should have 1 miss (first read)")
        assertEquals(1, stats.hitCount, "Should have 1 hit (second read)")
        assertEquals(50.0, stats.hitRate, 0.01)
    }

    @Test
    @DisplayName("LRU eviction - 페이지 제한 초과 시")
    fun `evicts pages when exceeding limit`() {
        // BufferPool with max 3 pages
        val smallBufferPool = BufferPool(maxPages = 3)
        val managerWithSmallCache = TableFileManager(tempDir, rowEncoder, smallBufferPool)

        val table = Table(
            tableName = "large_table",
            dataType = mapOf("id" to "INT", "data" to "VARCHAR"),
            rows = (1..200).map { mapOf("id" to it.toString(), "data" to "x".repeat(100)) }
        )

        managerWithSmallCache.writeTable(table)
        managerWithSmallCache.readTable("large_table")

        val stats = smallBufferPool.getStats()
        assertTrue(stats.totalPages <= 3, "Should not exceed max pages (actual: ${stats.totalPages})")
    }

    @Test
    @DisplayName("통계 정보 조회")
    fun `provides detailed statistics`() {
        val table = Table(
            tableName = "stats_test",
            dataType = mapOf("id" to "INT"),
            rows = (1..50).map { mapOf("id" to it.toString()) }
        )

        tableFileManager.writeTable(table)

        repeat(5) {
            tableFileManager.readTable("stats_test")
        }

        val stats = bufferPool.getStats()
        println(stats.toString())

        assertTrue(stats.totalPages > 0)
        assertTrue(stats.missCount > 0)
        assertTrue(stats.hitCount > 0)
        assertTrue(stats.hitRate > 0)
    }
}
