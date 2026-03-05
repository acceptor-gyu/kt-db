package study.db.server.vacuum

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import study.db.common.Row
import study.db.common.Table
import study.db.server.storage.*
import java.io.File

class VacuumServiceTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var rowEncoder: RowEncoder
    private lateinit var tableFileManager: TableFileManager
    private lateinit var vacuumLockManager: VacuumLockManager
    private lateinit var vacuumConfig: VacuumConfig
    private lateinit var vacuumService: VacuumService

    @BeforeEach
    fun setUp() {
        rowEncoder = RowEncoder(
            IntFieldEncoder(),
            VarcharFieldEncoder(),
            BooleanFieldEncoder(),
            TimestampFieldEncoder()
        )
        tableFileManager = TableFileManager(tempDir, rowEncoder, null)
        vacuumLockManager = VacuumLockManager()
        vacuumConfig = VacuumConfig().apply {
            enabled = true
            thresholdRatio = 0.3
            minDeletedRows = 10
            maxRetries = 3
            retryInitialDelayMs = 100
            retryMaxDelayMs = 1000
            diskSpaceCheckEnabled = false  // Disable for testing
        }
        vacuumService = VacuumService(tableFileManager, vacuumLockManager, vacuumConfig)
    }

    @AfterEach
    fun tearDown() {
        vacuumLockManager.clear()
    }

    @Test
    fun `testCalculateDeletedRatio - 삭제 비율 계산`() {
        // Given: 100개 행 중 30개 삭제
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 30)

        // When
        val ratio = vacuumService.calculateDeletedRatio(tableName)

        // Then
        assertEquals(0.3, ratio, 0.01, "Deleted ratio should be 30%")
    }

    @Test
    fun `testShouldVacuum - 임계값 이상일 때 true`() {
        // Given: 100개 행 중 35개 삭제 (35% > 30% threshold)
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 35)

        // When
        val shouldVacuum = vacuumService.shouldVacuum(tableName)

        // Then
        assertTrue(shouldVacuum, "Should vacuum when deleted ratio exceeds threshold")
    }

    @Test
    fun `testShouldVacuum - 임계값 이하일 때 false`() {
        // Given: 100개 행 중 20개 삭제 (20% < 30% threshold)
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 20)

        // When
        val shouldVacuum = vacuumService.shouldVacuum(tableName)

        // Then
        assertFalse(shouldVacuum, "Should not vacuum when deleted ratio is below threshold")
    }

    @Test
    fun `testShouldVacuum - 최소 행 수 미만일 때 false`() {
        // Given: 20개 행 중 8개 삭제 (40% > 30% threshold, but < 10 deleted rows)
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 20, deletedRows = 8)

        // When
        val shouldVacuum = vacuumService.shouldVacuum(tableName)

        // Then
        assertFalse(shouldVacuum, "Should not vacuum when deleted rows < minDeletedRows")
    }

    @Test
    fun `testVacuumTable - 성공 케이스`() {
        // Given: 100개 행 중 40개 삭제
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 40)

        val fileBefore = File(tempDir, "$tableName.dat")
        val sizeBefore = fileBefore.length()

        // When
        val stats = vacuumService.vacuumTable(tableName)

        // Then
        assertTrue(stats.success, "VACUUM should succeed")
        assertEquals(100, stats.totalRowsBefore, "Total rows before")
        assertEquals(60, stats.activeRows, "Active rows after")
        assertEquals(40, stats.deletedRowsRemoved, "Deleted rows removed")
        assertTrue(stats.diskSpaceSaved > 0, "Disk space should be saved")
        assertTrue(stats.durationMs >= 0, "Duration should be recorded")

        // 파일 크기 확인
        val sizeAfter = File(tempDir, "$tableName.dat").length()
        assertTrue(sizeAfter < sizeBefore, "File size should decrease after VACUUM")

        // 실제 데이터 확인
        val table = tableFileManager.readTable(tableName)
        assertNotNull(table)
        assertEquals(60, table!!.rows.size, "Should have 60 active rows")
    }

    @Test
    fun `testVacuumTable - 삭제된 행이 없을 때`() {
        // Given: 삭제된 행이 없는 테이블
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 50, deletedRows = 0)

        // When
        val stats = vacuumService.vacuumTable(tableName)

        // Then
        assertTrue(stats.success, "VACUUM should succeed (but do nothing)")
        assertEquals(0, stats.deletedRowsRemoved, "No deleted rows to remove")
        assertEquals("No deleted rows to remove", stats.errorMessage)
    }

    @Test
    fun `testVacuumTable - 존재하지 않는 테이블`() {
        // Given: 존재하지 않는 테이블
        val tableName = "nonexistent_table"

        // When
        val stats = vacuumService.vacuumTable(tableName)

        // Then
        assertFalse(stats.success, "VACUUM should fail for nonexistent table")
        assertTrue(stats.errorMessage?.contains("not found") ?: false)
    }

    @Test
    fun `testVacuumTable - VACUUM 비활성화 시`() {
        // Given: VACUUM 비활성화
        vacuumConfig.enabled = false
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 40)

        // When
        val stats = vacuumService.vacuumTable(tableName)

        // Then
        assertFalse(stats.success, "VACUUM should fail when disabled")
        assertTrue(stats.errorMessage?.contains("disabled") ?: false)
    }

    @Test
    fun `testTableExists - 테이블 존재 확인`() {
        // Given
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 10, deletedRows = 0)

        // When & Then
        assertTrue(vacuumService.tableExists(tableName))
        assertFalse(vacuumService.tableExists("nonexistent"))
    }

    // Helper method: 테이블 생성 및 일부 행 삭제
    private fun createTableWithDeletedRows(tableName: String, totalRows: Int, deletedRows: Int) {
        val schema = mapOf("id" to "INT", "name" to "VARCHAR")
        val rows = (1..totalRows).map { i ->
            Row(mapOf("id" to i.toString(), "name" to "User$i"), deleted = i <= deletedRows)
        }

        // 직접 파일에 저장 (deleted 행 포함)
        val file = File(tempDir, "$tableName.dat")
        val tempFile = File(tempDir, "$tableName.dat.tmp")

        java.io.RandomAccessFile(tempFile, "rw").use { raf ->
            // Header
            val buffer = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.BIG_ENDIAN)
            buffer.putShort(0xDBF0.toShort())
            buffer.putShort(1)
            buffer.putLong(rows.size.toLong())
            buffer.putInt(schema.size)
            val schemaLength = schema.entries.sumOf { (name, _) ->
                2 + name.toByteArray(Charsets.UTF_8).size + 1
            }
            buffer.putInt(schemaLength)
            buffer.putInt(0)
            raf.write(buffer.array())

            // Schema
            schema.forEach { (columnName, typeName) ->
                val nameBytes = columnName.toByteArray(Charsets.UTF_8)
                val schemaBuffer = java.nio.ByteBuffer.allocate(2 + nameBytes.size + 1)
                    .order(java.nio.ByteOrder.BIG_ENDIAN)
                schemaBuffer.putShort(nameBytes.size.toShort())
                schemaBuffer.put(nameBytes)
                val typeTag = when (typeName) {
                    "INT" -> 0x01.toByte()
                    "VARCHAR" -> 0x02.toByte()
                    else -> throw IllegalArgumentException("Unsupported type: $typeName")
                }
                schemaBuffer.put(typeTag)
                raf.write(schemaBuffer.array())
            }

            // Rows
            rows.forEach { row ->
                val rowBytes = rowEncoder.encodeRow(row, schema)
                raf.write(rowBytes)
            }

            raf.fd.sync()
        }

        tempFile.renameTo(file)
    }
}
