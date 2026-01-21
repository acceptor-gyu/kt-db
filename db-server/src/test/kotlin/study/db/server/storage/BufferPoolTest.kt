package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BufferPool 테스트")
class BufferPoolTest {

    private lateinit var bufferPool: BufferPool

    @BeforeEach
    fun setUp() {
        bufferPool = BufferPool(maxPages = 3)  // 작은 캐시로 테스트
    }

    @Test
    @DisplayName("Cache miss - 페이지 로드")
    fun `loads page on cache miss`() {
        val pageId = PageId("users", 0)
        val mockPage = Page(pageId, ByteArray(16))

        var loaderCalled = false
        val result = bufferPool.getPage(pageId) {
            loaderCalled = true
            mockPage
        }

        assertTrue(loaderCalled, "Loader should be called on cache miss")
        assertNotNull(result)
        assertEquals(pageId, result?.pageId)
    }

    @Test
    @DisplayName("Cache hit - 로더 호출 안 함")
    fun `does not call loader on cache hit`() {
        val pageId = PageId("users", 0)
        val mockPage = Page(pageId, ByteArray(16))

        // First access - cache miss
        bufferPool.getPage(pageId) { mockPage }

        // Second access - cache hit
        var loaderCalled = false
        val result = bufferPool.getPage(pageId) {
            loaderCalled = true
            mockPage
        }

        assertFalse(loaderCalled, "Loader should not be called on cache hit")
        assertNotNull(result)
    }

    @Test
    @DisplayName("Hit rate 계산")
    fun `calculates hit rate correctly`() {
        val page1 = PageId("users", 0)
        val page2 = PageId("users", 1)

        // 2 misses
        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }
        bufferPool.getPage(page2) { Page(page2, ByteArray(16)) }

        // 2 hits
        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }
        bufferPool.getPage(page2) { Page(page2, ByteArray(16)) }

        // Hit rate = 2 / (2 + 2) = 50%
        assertEquals(50.0, bufferPool.getHitRate(), 0.01)
    }

    @Test
    @DisplayName("LRU eviction - 가장 오래된 페이지 제거")
    fun `evicts least recently used page`() {
        val page1 = PageId("users", 0)
        val page2 = PageId("users", 1)
        val page3 = PageId("users", 2)
        val page4 = PageId("users", 3)

        // Fill cache (max 3 pages)
        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }
        Thread.sleep(10)  // 시간차 보장
        bufferPool.getPage(page2) { Page(page2, ByteArray(16)) }
        Thread.sleep(10)
        bufferPool.getPage(page3) { Page(page3, ByteArray(16)) }

        // Access page1 again (update LRU)
        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }

        // Add page4 - should evict page2 (least recently used)
        Thread.sleep(10)
        bufferPool.getPage(page4) { Page(page4, ByteArray(16)) }

        val stats = bufferPool.getStats()
        assertEquals(3, stats.totalPages, "Should maintain max pages")
    }

    @Test
    @DisplayName("putPage - dirty page marking")
    fun `marks page as dirty on put`() {
        val pageId = PageId("users", 0)
        val page = Page(pageId, ByteArray(16))

        bufferPool.putPage(pageId, page)

        val stats = bufferPool.getStats()
        assertEquals(1, stats.dirtyPages)
    }

    @Test
    @DisplayName("invalidateTable - 테이블의 모든 페이지 제거")
    fun `invalidates all pages for a table`() {
        val page1 = PageId("users", 0)
        val page2 = PageId("users", 1)
        val page3 = PageId("products", 0)

        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }
        bufferPool.getPage(page2) { Page(page2, ByteArray(16)) }
        bufferPool.getPage(page3) { Page(page3, ByteArray(16)) }

        bufferPool.invalidateTable("users")

        val stats = bufferPool.getStats()
        assertEquals(1, stats.totalPages, "Should only have 'products' page")
    }

    @Test
    @DisplayName("invalidatePage - 특정 페이지 제거")
    fun `invalidates specific page`() {
        val pageId = PageId("users", 0)
        bufferPool.getPage(pageId) { Page(pageId, ByteArray(16)) }

        bufferPool.invalidatePage(pageId)

        val stats = bufferPool.getStats()
        assertEquals(0, stats.totalPages)
    }

    @Test
    @DisplayName("clear - 캐시 초기화")
    fun `clears all pages and stats`() {
        val page1 = PageId("users", 0)
        val page2 = PageId("users", 1)

        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }
        bufferPool.getPage(page2) { Page(page2, ByteArray(16)) }
        bufferPool.putPage(page1, Page(page1, ByteArray(16)))

        bufferPool.clear()

        val stats = bufferPool.getStats()
        assertEquals(0, stats.totalPages)
        assertEquals(0, stats.dirtyPages)
        assertEquals(0, stats.hitCount)
        assertEquals(0, stats.missCount)
        assertEquals(0.0, stats.hitRate)
    }

    @Test
    @DisplayName("getStats - 통계 정보 조회")
    fun `returns correct statistics`() {
        val page1 = PageId("users", 0)
        val page2 = PageId("users", 1)

        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }
        bufferPool.putPage(page2, Page(page2, ByteArray(16)))
        bufferPool.getPage(page1) { Page(page1, ByteArray(16)) }

        val stats = bufferPool.getStats()

        assertEquals(2, stats.totalPages)
        assertEquals(1, stats.dirtyPages)
        assertEquals(1, stats.hitCount)
        assertEquals(1, stats.missCount)
        assertEquals(50.0, stats.hitRate, 0.01)
    }

    @Test
    @DisplayName("Thread safety - 동시 접근")
    fun `handles concurrent access safely`() {
        val bufferPool = BufferPool(maxPages = 100)
        val threads = (0 until 10).map { threadId ->
            Thread {
                repeat(10) { pageNum ->
                    val pageId = PageId("table_$threadId", pageNum)
                    bufferPool.getPage(pageId) {
                        Page(pageId, ByteArray(16))
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val stats = bufferPool.getStats()
        assertTrue(stats.totalPages > 0)
        assertTrue(stats.totalPages <= 100)
    }

    @Test
    @DisplayName("Loader returns null - null 반환")
    fun `returns null when loader returns null`() {
        val pageId = PageId("users", 0)

        val result = bufferPool.getPage(pageId) { null }

        assertNull(result)
    }
}
