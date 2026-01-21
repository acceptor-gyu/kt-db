package study.db.server.storage

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * BufferPool - Page 단위 메모리 캐시 (MySQL InnoDB Buffer Pool과 유사)
 *
 * 주요 기능:
 * - LRU (Least Recently Used) eviction policy
 * - Dirty page tracking (변경된 페이지 추적)
 * - Thread-safe (ConcurrentHashMap 사용)
 * - Hit/miss 통계
 *
 * @param maxPages 최대 캐시 페이지 수 (기본: 1024 = 16MB)
 */
class BufferPool(
    private val maxPages: Int = 1024  // 16KB * 1024 = 16MB
) {
    private val logger = LoggerFactory.getLogger(BufferPool::class.java)

    // Page cache (thread-safe)
    private val pages = ConcurrentHashMap<PageId, Page>()

    // LRU tracking: pageId -> last access time (nanoseconds)
    private val pageAccessMap = ConcurrentHashMap<PageId, Long>()

    // Dirty page tracking (변경된 페이지)
    private val dirtyPages = ConcurrentHashMap.newKeySet<PageId>()

    // Statistics
    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    /**
     * 페이지 조회 (cache hit/miss 처리)
     *
     * @param pageId 페이지 식별자
     * @param loader 캐시 미스 시 페이지를 로드하는 함수
     * @return Page 객체 또는 null
     */
    fun getPage(pageId: PageId, loader: () -> Page?): Page? {
        // 1. Cache hit
        pages[pageId]?.let { page ->
            hitCount.incrementAndGet()
            updateLRU(pageId)
            logger.debug("Cache hit: $pageId (hit rate: ${getHitRate()}%)")
            return page
        }

        // 2. Cache miss - load from disk
        missCount.incrementAndGet()
        val page = loader() ?: return null

        // 3. Evict if necessary
        evictIfNecessary()

        // 4. Add to cache
        pages[pageId] = page
        updateLRU(pageId)

        logger.debug("Cache miss: loaded $pageId (hit rate: ${getHitRate()}%)")
        return page
    }

    /**
     * 페이지 쓰기 (dirty page marking)
     *
     * @param pageId 페이지 식별자
     * @param page 페이지 객체
     */
    fun putPage(pageId: PageId, page: Page) {
        evictIfNecessary()
        pages[pageId] = page
        dirtyPages.add(pageId)
        updateLRU(pageId)
        logger.debug("Page written to cache: $pageId (marked as dirty)")
    }

    /**
     * LRU 업데이트 (최근 접근 시간 갱신)
     */
    private fun updateLRU(pageId: PageId) {
        pageAccessMap[pageId] = System.nanoTime()
    }

    /**
     * Eviction (LRU 기반으로 페이지 제거)
     */
    private fun evictIfNecessary() {
        if (pages.size < maxPages) return

        // Find LRU page (가장 오래 전에 접근한 페이지)
        val lruPageId = pageAccessMap.entries
            .minByOrNull { it.value }
            ?.key ?: return

        // Flush if dirty (변경된 페이지면 디스크에 기록 필요)
        if (dirtyPages.contains(lruPageId)) {
            logger.info("Evicting dirty page: $lruPageId (flush required)")
            // TODO: flushPage(lruPageId) - TableFileManager.writePage() 호출
        }

        // Remove from cache
        pages.remove(lruPageId)
        pageAccessMap.remove(lruPageId)
        dirtyPages.remove(lruPageId)

        logger.debug("Evicted page: $lruPageId (cache size: ${pages.size})")
    }

    /**
     * 모든 dirty pages flush (변경된 페이지를 디스크에 기록)
     */
    fun flushAll() {
        dirtyPages.forEach { pageId ->
            logger.info("Flushing dirty page: $pageId")
            // TODO: flushPage(pageId)
        }
        dirtyPages.clear()
    }

    /**
     * 테이블의 모든 페이지 무효화 (DROP, ALTER 시 사용)
     *
     * @param tableName 테이블 이름
     */
    fun invalidateTable(tableName: String) {
        val toRemove = pages.keys.filter { it.tableName == tableName }

        toRemove.forEach { pageId ->
            if (dirtyPages.contains(pageId)) {
                logger.info("Invalidating dirty page: $pageId")
                // TODO: flushPage(pageId)
            }
            pages.remove(pageId)
            pageAccessMap.remove(pageId)
            dirtyPages.remove(pageId)
        }

        logger.info("Invalidated ${toRemove.size} pages for table: $tableName")
    }

    /**
     * 특정 페이지를 캐시에서 제거 (invalidate)
     *
     * @param pageId 페이지 식별자
     */
    fun invalidatePage(pageId: PageId) {
        if (dirtyPages.contains(pageId)) {
            logger.info("Invalidating dirty page: $pageId")
            // TODO: flushPage(pageId)
        }
        pages.remove(pageId)
        pageAccessMap.remove(pageId)
        dirtyPages.remove(pageId)
    }

    /**
     * Cache hit rate 계산 (%)
     */
    fun getHitRate(): Double {
        val total = hitCount.get() + missCount.get()
        return if (total == 0L) 0.0 else (hitCount.get() * 100.0) / total
    }

    /**
     * 통계 정보 조회
     */
    fun getStats(): BufferPoolStats {
        return BufferPoolStats(
            totalPages = pages.size,
            maxPages = maxPages,
            dirtyPages = dirtyPages.size,
            hitCount = hitCount.get(),
            missCount = missCount.get(),
            hitRate = getHitRate()
        )
    }

    /**
     * 캐시 초기화 (테스트용)
     */
    fun clear() {
        flushAll()
        pages.clear()
        pageAccessMap.clear()
        dirtyPages.clear()
        hitCount.set(0)
        missCount.set(0)
        logger.info("Buffer pool cleared")
    }
}

/**
 * BufferPool 통계 정보
 */
data class BufferPoolStats(
    val totalPages: Int,
    val maxPages: Int,
    val dirtyPages: Int,
    val hitCount: Long,
    val missCount: Long,
    val hitRate: Double
) {
    override fun toString(): String {
        return """
            BufferPool Stats:
            - Total Pages: $totalPages / $maxPages
            - Dirty Pages: $dirtyPages
            - Hit Count: $hitCount
            - Miss Count: $missCount
            - Hit Rate: ${"%.2f".format(hitRate)}%
        """.trimIndent()
    }
}
