package study.db.server.vacuum

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 테이블별 Read-Write Lock 관리
 *
 * VACUUM 작업 시 동시성 제어를 위한 락 관리자입니다.
 * - Read lock: SELECT/INSERT/DELETE 작업
 * - Write lock: VACUUM 파일 교체 작업
 */
@Component
class VacuumLockManager {
    private val logger = LoggerFactory.getLogger(VacuumLockManager::class.java)
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    /**
     * 테이블의 Read-Write Lock을 가져옵니다 (없으면 생성)
     */
    private fun getLock(tableName: String): ReentrantReadWriteLock {
        return locks.computeIfAbsent(tableName) { ReentrantReadWriteLock() }
    }

    /**
     * Read lock 획득 시도
     *
     * @param tableName 테이블 이름
     * @return 성공 여부
     */
    fun acquireReadLock(tableName: String): Boolean {
        return try {
            val lock = getLock(tableName)
            lock.readLock().lock()
            logger.debug("Read lock acquired for table: {}", tableName)
            true
        } catch (e: Exception) {
            logger.error("Failed to acquire read lock for table: {}", tableName, e)
            false
        }
    }

    /**
     * Read lock 해제
     *
     * @param tableName 테이블 이름
     */
    fun releaseReadLock(tableName: String) {
        try {
            val lock = getLock(tableName)
            lock.readLock().unlock()
            logger.debug("Read lock released for table: {}", tableName)
        } catch (e: Exception) {
            logger.error("Failed to release read lock for table: {}", tableName, e)
        }
    }

    /**
     * Write lock 획득 시도 (타임아웃 지원)
     *
     * @param tableName 테이블 이름
     * @param timeoutMs 타임아웃 (밀리초)
     * @return 성공 여부
     */
    fun acquireWriteLock(tableName: String, timeoutMs: Long = 5000): Boolean {
        return try {
            val lock = getLock(tableName)
            val acquired = lock.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)
            if (acquired) {
                logger.debug("Write lock acquired for table: {}", tableName)
            } else {
                logger.warn("Write lock acquisition timeout for table: {}", tableName)
            }
            acquired
        } catch (e: InterruptedException) {
            logger.error("Write lock acquisition interrupted for table: {}", tableName, e)
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            logger.error("Failed to acquire write lock for table: {}", tableName, e)
            false
        }
    }

    /**
     * Write lock 해제
     *
     * @param tableName 테이블 이름
     */
    fun releaseWriteLock(tableName: String) {
        try {
            val lock = getLock(tableName)
            lock.writeLock().unlock()
            logger.debug("Write lock released for table: {}", tableName)
        } catch (e: Exception) {
            logger.error("Failed to release write lock for table: {}", tableName, e)
        }
    }

    /**
     * Write lock이 잡혀있는지 확인
     *
     * @param tableName 테이블 이름
     * @return Write lock 보유 여부
     */
    fun isWriteLocked(tableName: String): Boolean {
        val lock = locks[tableName] ?: return false
        return lock.isWriteLocked
    }

    /**
     * 특정 테이블의 락 제거 (테스트용)
     */
    fun removeLock(tableName: String) {
        locks.remove(tableName)
        logger.debug("Lock removed for table: {}", tableName)
    }

    /**
     * 모든 락 제거 (테스트용)
     */
    fun clear() {
        locks.clear()
        logger.debug("All locks cleared")
    }
}
