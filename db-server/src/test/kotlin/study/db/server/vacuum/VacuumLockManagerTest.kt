package study.db.server.vacuum

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VacuumLockManagerTest {
    private lateinit var lockManager: VacuumLockManager

    @BeforeEach
    fun setUp() {
        lockManager = VacuumLockManager()
    }

    @AfterEach
    fun tearDown() {
        lockManager.clear()
    }

    @Test
    fun `testAcquireReadLock - 여러 스레드가 동시에 Read lock 획득 가능`() {
        val tableName = "test_table"
        val numThreads = 5
        val latch = CountDownLatch(numThreads)
        val results = mutableListOf<Boolean>()

        repeat(numThreads) {
            thread {
                val acquired = lockManager.acquireReadLock(tableName)
                synchronized(results) {
                    results.add(acquired)
                }
                Thread.sleep(100)  // Hold lock briefly
                lockManager.releaseReadLock(tableName)
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)

        // 모든 스레드가 Read lock을 획득할 수 있어야 함
        assertEquals(numThreads, results.size)
        assertTrue(results.all { it }, "All read locks should be acquired")
    }

    @Test
    fun `testAcquireWriteLock - Write lock은 배타적으로 획득됨`() {
        val tableName = "test_table"
        val latch = CountDownLatch(1)
        val secondLockAcquired = mutableListOf<Boolean>()

        // 첫 번째 Write lock 획득
        val acquired1 = lockManager.acquireWriteLock(tableName, timeoutMs = 100)
        assertTrue(acquired1, "First write lock should be acquired")

        // 다른 스레드에서 두 번째 Write lock 획득 시도 (실패해야 함)
        thread {
            val acquired = lockManager.acquireWriteLock(tableName, timeoutMs = 100)
            synchronized(secondLockAcquired) {
                secondLockAcquired.add(acquired)
            }
            if (acquired) {
                lockManager.releaseWriteLock(tableName)
            }
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)

        synchronized(secondLockAcquired) {
            assertEquals(1, secondLockAcquired.size)
            assertFalse(secondLockAcquired[0], "Second write lock should fail")
        }

        // 첫 번째 lock 해제
        lockManager.releaseWriteLock(tableName)

        // 이제 획득 가능해야 함
        val acquired3 = lockManager.acquireWriteLock(tableName, timeoutMs = 100)
        assertTrue(acquired3, "Write lock should be acquired after release")

        lockManager.releaseWriteLock(tableName)
    }

    @Test
    fun `testWriteLockBlocksReads - Write lock이 잡히면 Read lock이 블로킹됨`() {
        val tableName = "test_table"
        val readLockAcquired = mutableListOf<Boolean>()
        val latch = CountDownLatch(1)

        // Write lock 획득
        lockManager.acquireWriteLock(tableName)

        // 다른 스레드에서 Read lock 시도
        thread {
            // Write lock이 잡혀있어서 Read lock 획득이 지연됨
            val acquired = lockManager.acquireReadLock(tableName)
            synchronized(readLockAcquired) {
                readLockAcquired.add(acquired)
            }
            lockManager.releaseReadLock(tableName)
            latch.countDown()
        }

        // 잠시 대기 (Read lock이 블로킹되는지 확인)
        Thread.sleep(200)
        synchronized(readLockAcquired) {
            assertTrue(readLockAcquired.isEmpty(), "Read lock should be blocked by write lock")
        }

        // Write lock 해제
        lockManager.releaseWriteLock(tableName)

        // Read lock이 이제 획득되어야 함
        latch.await(5, TimeUnit.SECONDS)
        synchronized(readLockAcquired) {
            assertEquals(1, readLockAcquired.size)
            assertTrue(readLockAcquired[0], "Read lock should be acquired after write lock release")
        }
    }

    @Test
    fun `testLockTimeout - Write lock 획득 타임아웃`() {
        val tableName = "test_table"
        val latch = CountDownLatch(1)
        val results = mutableListOf<Pair<Boolean, Long>>()

        // Write lock 획득
        lockManager.acquireWriteLock(tableName)

        // 다른 스레드에서 짧은 타임아웃으로 두 번째 Write lock 시도
        thread {
            val start = System.currentTimeMillis()
            val acquired = lockManager.acquireWriteLock(tableName, timeoutMs = 500)
            val elapsed = System.currentTimeMillis() - start
            synchronized(results) {
                results.add(Pair(acquired, elapsed))
            }
            if (acquired) {
                lockManager.releaseWriteLock(tableName)
            }
            latch.countDown()
        }

        latch.await(5, TimeUnit.SECONDS)

        synchronized(results) {
            assertEquals(1, results.size)
            val (acquired, elapsed) = results[0]
            assertFalse(acquired, "Second write lock should timeout")
            assertTrue(elapsed >= 500, "Should wait for timeout period: ${elapsed}ms")
            assertTrue(elapsed < 1000, "Should not wait too long: ${elapsed}ms")
        }

        lockManager.releaseWriteLock(tableName)
    }

    @Test
    fun `testIsWriteLocked - Write lock 상태 확인`() {
        val tableName = "test_table"

        // 초기 상태: Write lock 없음
        assertFalse(lockManager.isWriteLocked(tableName))

        // Write lock 획득
        lockManager.acquireWriteLock(tableName)
        assertTrue(lockManager.isWriteLocked(tableName))

        // Write lock 해제
        lockManager.releaseWriteLock(tableName)
        assertFalse(lockManager.isWriteLocked(tableName))
    }

    @Test
    fun `testReadLockDoesNotAffectIsWriteLocked - Read lock은 Write lock 상태에 영향 없음`() {
        val tableName = "test_table"

        // Read lock 획득
        lockManager.acquireReadLock(tableName)
        assertFalse(lockManager.isWriteLocked(tableName), "Read lock should not be detected as write lock")

        lockManager.releaseReadLock(tableName)
    }

    @Test
    fun `testMultipleTablesIndependentLocks - 각 테이블은 독립적인 lock을 가짐`() {
        val table1 = "table1"
        val table2 = "table2"

        // table1에 Write lock
        lockManager.acquireWriteLock(table1)

        // table2에는 Write lock 획득 가능
        val acquired = lockManager.acquireWriteLock(table2, timeoutMs = 100)
        assertTrue(acquired, "Different table should have independent lock")

        assertTrue(lockManager.isWriteLocked(table1))
        assertTrue(lockManager.isWriteLocked(table2))

        lockManager.releaseWriteLock(table1)
        lockManager.releaseWriteLock(table2)
    }

    @Test
    fun `testRemoveLock - 특정 테이블의 lock 제거`() {
        val tableName = "test_table"

        lockManager.acquireWriteLock(tableName)
        assertTrue(lockManager.isWriteLocked(tableName))

        lockManager.releaseWriteLock(tableName)
        lockManager.removeLock(tableName)

        // 새로운 lock이 생성되어야 함
        lockManager.acquireWriteLock(tableName)
        assertTrue(lockManager.isWriteLocked(tableName))

        lockManager.releaseWriteLock(tableName)
    }
}
