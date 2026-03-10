package study.db.server.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import study.db.server.storage.*
import study.db.server.vacuum.VacuumConfig
import study.db.server.vacuum.VacuumLockManager
import study.db.server.vacuum.VacuumService
import java.io.File

@DisplayName("DELETE + VACUUM 통합 테스트")
class DeleteVacuumIntegrationTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var tableFileManager: TableFileManager
    private lateinit var tableService: TableService
    private lateinit var vacuumService: VacuumService

    @BeforeEach
    fun setUp() {
        val rowEncoder = RowEncoder(
            IntFieldEncoder(),
            VarcharFieldEncoder(),
            BooleanFieldEncoder(),
            TimestampFieldEncoder()
        )
        tableFileManager = TableFileManager(tempDir, rowEncoder)

        val vacuumLockManager = VacuumLockManager()
        val vacuumConfig = VacuumConfig().apply {
            enabled = true
            thresholdRatio = 0.0
            minDeletedRows = 0
            maxRetries = 5
            retryInitialDelayMs = 100
            retryMaxDelayMs = 1000
            diskSpaceCheckEnabled = false
        }
        vacuumService = VacuumService(tableFileManager, vacuumLockManager, vacuumConfig)

        tableService = TableService(tableFileManager)
        tableService.setVacuumService(vacuumService)
    }

    private fun createUsersWithRows(count: Int) {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
        for (i in 1..count) {
            tableService.insert("users", mapOf("id" to i.toString(), "name" to "User$i"))
        }
    }

    @Test
    @DisplayName("DELETE 후 VACUUM 시 tombstone 행이 물리적으로 제거됨")
    fun `vacuum removes tombstone rows after delete`() {
        // Given: 5개 행 삽입 후 2개 삭제
        createUsersWithRows(5)
        tableService.delete("users", "id=1 OR id=3")

        // 중간 검증: tombstone 존재
        val statsBefore = tableFileManager.getTableStatistics("users")
        assertEquals(2, statsBefore!!.deletedRows)

        // When: VACUUM 실행
        val vacuumStats = tableService.vacuum("users")

        // Then: tombstone 제거됨
        assertTrue(vacuumStats.success)
        val statsAfter = tableFileManager.getTableStatistics("users")
        assertEquals(3, statsAfter!!.totalRows)
        assertEquals(0, statsAfter.deletedRows)
        assertEquals(3, statsAfter.activeRows)
    }

    @Test
    @DisplayName("VACUUM 후 파일 크기 감소")
    fun `file size decreases after vacuum`() {
        // Given: 20개 행 삽입 후 10개 삭제
        createUsersWithRows(20)
        tableService.delete("users", "id > 10")

        val fileBefore = File(tempDir, "users.dat")
        val sizeBefore = fileBefore.length()

        // When: VACUUM 실행
        val vacuumStats = tableService.vacuum("users")

        // Then: 파일 크기 감소
        assertTrue(vacuumStats.success)
        val sizeAfter = File(tempDir, "users.dat").length()
        assertTrue(sizeAfter < sizeBefore, "파일 크기가 감소해야 함: before=$sizeBefore, after=$sizeAfter")
        assertTrue(vacuumStats.diskSpaceSaved > 0)
    }

    @Test
    @DisplayName("VACUUM 후 SELECT 결과 동일")
    fun `select results are identical before and after vacuum`() {
        // Given: 5개 행 삽입 후 id=3 삭제
        createUsersWithRows(5)
        tableService.delete("users", "id=3")

        // DELETE 후 SELECT 결과 기록
        val resultBeforeVacuum = tableService.select("users")

        // When: VACUUM 실행
        tableService.vacuum("users")

        // Then: VACUUM 후 SELECT 결과 동일
        val resultAfterVacuum = tableService.select("users")
        assertEquals(resultBeforeVacuum?.rows?.size, resultAfterVacuum?.rows?.size)
        assertEquals(
            resultBeforeVacuum?.rows?.sortedBy { it["id"] },
            resultAfterVacuum?.rows?.sortedBy { it["id"] }
        )
    }

    @Test
    @DisplayName("DELETE 전체 행 후 VACUUM 시 빈 테이블")
    fun `vacuum after full delete results in empty table`() {
        // Given: 5개 행 삽입 후 전체 삭제
        createUsersWithRows(5)
        tableService.delete("users", null)

        // When: VACUUM 실행
        tableService.vacuum("users")

        // Then: 빈 테이블
        val result = tableService.select("users")
        assertEquals(0, result?.rows?.size)
        val stats = tableFileManager.getTableStatistics("users")
        assertEquals(0, stats!!.totalRows)
    }

    @Test
    @DisplayName("대량 DELETE 후 VACUUM 통합 시나리오")
    fun `vacuum after bulk delete with large dataset`() {
        // Given: large_table에 1000행 삽입
        tableService.createTable("large_table", mapOf("id" to "INT", "name" to "VARCHAR"))
        for (i in 1..1000) {
            tableService.insert("large_table", mapOf("id" to i.toString(), "name" to "User$i"))
        }

        // When: 500행 삭제 후 VACUUM
        tableService.delete("large_table", "id > 500")
        val sizeBefore = File(tempDir, "large_table.dat").length()
        tableService.vacuum("large_table")

        // Then: 500행만 존재, 파일 크기 감소
        val result = tableService.select("large_table")
        assertEquals(500, result?.rows?.size)
        val sizeAfter = File(tempDir, "large_table.dat").length()
        assertTrue(sizeAfter < sizeBefore)
    }

    @Test
    @DisplayName("DELETE + INSERT + VACUUM 혼합 시나리오")
    fun `mixed delete insert vacuum scenario`() {
        // Given: 3개 행 삽입
        createUsersWithRows(3)

        // When: Bob 삭제 -> Dave 추가 -> VACUUM
        tableService.delete("users", "id=2")
        tableService.insert("users", mapOf("id" to "4", "name" to "Dave"))
        tableService.vacuum("users")

        // Then: User1, User3, Dave 존재 (3행), User2(Bob) 없음
        val result = tableService.select("users")
        assertEquals(3, result?.rows?.size)
        val names = result?.rows?.map { it["name"] }?.toSet()
        assertTrue(names!!.contains("User1"))
        assertTrue(names.contains("User3"))
        assertTrue(names.contains("Dave"))
        assertFalse(names.contains("User2"))
    }
}
