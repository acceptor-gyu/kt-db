package study.db.server.vacuum

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import study.db.common.Row
import study.db.server.storage.*
import java.io.File
import java.util.concurrent.TimeUnit

class VacuumSchedulerTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var rowEncoder: RowEncoder
    private lateinit var tableFileManager: TableFileManager
    private lateinit var vacuumLockManager: VacuumLockManager
    private lateinit var vacuumConfig: VacuumConfig
    private lateinit var vacuumService: VacuumService
    private lateinit var vacuumScheduler: VacuumScheduler

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
            scanIntervalSeconds = 1  // 테스트용: 1초 간격
            maxRetries = 3
            diskSpaceCheckEnabled = false
        }
        vacuumService = VacuumService(tableFileManager, vacuumLockManager, vacuumConfig)
        vacuumScheduler = VacuumScheduler(vacuumService, tableFileManager, vacuumConfig)
    }

    @AfterEach
    fun tearDown() {
        vacuumScheduler.stop()
        vacuumLockManager.clear()
    }

    @Test
    fun `testStart - 스케줄러 시작`() {
        // When
        vacuumScheduler.start()

        // Then
        assertTrue(vacuumScheduler.isRunning(), "Scheduler should be running")
    }

    @Test
    fun `testStop - 스케줄러 중지`() {
        // Given
        vacuumScheduler.start()
        assertTrue(vacuumScheduler.isRunning())

        // When
        vacuumScheduler.stop()

        // Then
        assertFalse(vacuumScheduler.isRunning(), "Scheduler should be stopped")
    }

    @Test
    fun `testStart - 이미 실행 중일 때 중복 시작 방지`() {
        // Given
        vacuumScheduler.start()
        assertTrue(vacuumScheduler.isRunning())

        // When: 다시 시작 시도
        vacuumScheduler.start()

        // Then: 여전히 실행 중
        assertTrue(vacuumScheduler.isRunning())
    }

    @Test
    fun `testStop - 실행 중이 아닐 때 중지 시도`() {
        // Given: 스케줄러가 실행 중이 아님
        assertFalse(vacuumScheduler.isRunning())

        // When: 중지 시도
        vacuumScheduler.stop()

        // Then: 에러 없이 정상 종료
        assertFalse(vacuumScheduler.isRunning())
    }

    @Test
    fun `testScanAndVacuum - 임계값 초과 테이블을 자동으로 VACUUM`() {
        // Given: 임계값을 초과하는 테이블 생성
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 40)

        val fileBefore = File(tempDir, "$tableName.dat")
        val sizeBefore = fileBefore.length()

        // When: 수동 스캔 트리거
        vacuumScheduler.triggerScan()

        // Then: VACUUM이 실행되어 파일 크기 감소
        Thread.sleep(500)  // VACUUM 완료 대기

        val sizeAfter = File(tempDir, "$tableName.dat").length()
        assertTrue(sizeAfter < sizeBefore, "File should be compacted after automatic VACUUM")

        // 데이터 확인
        val table = tableFileManager.readTable(tableName)
        assertEquals(60, table?.rows?.size, "Should have 60 active rows after VACUUM")
    }

    @Test
    fun `testScanAndVacuum - 임계값 미만 테이블은 VACUUM하지 않음`() {
        // Given: 임계값 미만의 테이블
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 20)  // 20% < 30%

        val fileBefore = File(tempDir, "$tableName.dat")
        val sizeBefore = fileBefore.length()

        // When
        vacuumScheduler.triggerScan()

        // Then: VACUUM이 실행되지 않아 파일 크기 동일
        Thread.sleep(500)

        val sizeAfter = File(tempDir, "$tableName.dat").length()
        assertEquals(sizeBefore, sizeAfter, "File size should remain the same (no VACUUM)")
    }

    @Test
    fun `testScanAndVacuum - 테이블이 없을 때`() {
        // Given: 테이블이 없음
        assertEquals(0, tableFileManager.listAllTables().size)

        // When
        vacuumScheduler.triggerScan()

        // Then: 에러 없이 정상 종료
        Thread.sleep(100)
    }

    @Test
    fun `testScheduler - 주기적 실행 확인`() {
        // Given: 임계값을 초과하는 테이블
        val tableName = "test_table"
        createTableWithDeletedRows(tableName, totalRows = 100, deletedRows = 40)

        val sizeBefore = File(tempDir, "$tableName.dat").length()

        // When: 스케줄러 시작 (1초 간격)
        vacuumScheduler.start()

        // Then: 2초 대기 후 VACUUM이 자동 실행되어야 함
        Thread.sleep(2500)

        val sizeAfter = File(tempDir, "$tableName.dat").length()
        assertTrue(sizeAfter < sizeBefore, "Scheduler should automatically trigger VACUUM")

        vacuumScheduler.stop()
    }

    @Test
    fun `testScheduler - VACUUM 비활성화 시 실행하지 않음`() {
        // Given: VACUUM 비활성화
        vacuumConfig.enabled = false

        // When
        vacuumScheduler.start()

        // Then: 스케줄러가 실행되지 않음
        assertFalse(vacuumScheduler.isRunning(), "Scheduler should not start when VACUUM is disabled")
    }

    // Helper method
    private fun createTableWithDeletedRows(tableName: String, totalRows: Int, deletedRows: Int) {
        val schema = mapOf("id" to "INT", "name" to "VARCHAR")
        val rows = (1..totalRows).map { i ->
            Row(mapOf("id" to i.toString(), "name" to "User$i"), deleted = i <= deletedRows)
        }

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
