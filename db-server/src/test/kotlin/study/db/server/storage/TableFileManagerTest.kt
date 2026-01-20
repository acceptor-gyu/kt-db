package study.db.server.storage

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import study.db.common.Table
import java.io.File

@DisplayName("TableFileManager 테스트")
class TableFileManagerTest {

    private lateinit var tempDir: File
    private lateinit var tableFileManager: TableFileManager
    private lateinit var rowEncoder: RowEncoder

    @BeforeEach
    fun setUp() {
        // 임시 디렉토리 생성
        tempDir = createTempDir("table_file_manager_test")
        rowEncoder = RowEncoder(
            IntFieldEncoder(),
            VarcharFieldEncoder(),
            BooleanFieldEncoder(),
            TimestampFieldEncoder()
        )
        tableFileManager = TableFileManager(tempDir, rowEncoder)
    }

    @AfterEach
    fun tearDown() {
        // 임시 디렉토리 삭제
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("빈 테이블 write/read")
    fun `writes and reads empty table`() {
        val table = Table(
            tableName = "empty_table",
            dataType = mapOf("id" to "INT", "name" to "VARCHAR"),
            rows = emptyList()
        )

        tableFileManager.writeTable(table)
        val loaded = tableFileManager.readTable("empty_table")

        assertNotNull(loaded)
        assertEquals("empty_table", loaded?.tableName)
        assertEquals(table.dataType, loaded?.dataType)
        assertEquals(0, loaded?.rows?.size)
    }

    @Test
    @DisplayName("단일 행 테이블 write/read")
    fun `writes and reads table with single row`() {
        val table = Table(
            tableName = "users",
            dataType = mapOf("id" to "INT", "name" to "VARCHAR"),
            rows = listOf(
                mapOf("id" to "1", "name" to "John")
            )
        )

        tableFileManager.writeTable(table)
        val loaded = tableFileManager.readTable("users")

        assertNotNull(loaded)
        assertEquals("users", loaded?.tableName)
        assertEquals(1, loaded?.rows?.size)
        assertEquals("1", loaded?.rows?.get(0)?.get("id"))
        assertEquals("John", loaded?.rows?.get(0)?.get("name"))
    }

    @Test
    @DisplayName("여러 행과 모든 타입 write/read")
    fun `writes and reads table with multiple rows and all types`() {
        val table = Table(
            tableName = "test_table",
            dataType = mapOf(
                "id" to "INT",
                "name" to "VARCHAR",
                "active" to "BOOLEAN",
                "created_at" to "TIMESTAMP"
            ),
            rows = listOf(
                mapOf(
                    "id" to "1",
                    "name" to "Alice",
                    "active" to "true",
                    "created_at" to "2024-01-15T10:30:00Z"
                ),
                mapOf(
                    "id" to "2",
                    "name" to "Bob",
                    "active" to "false",
                    "created_at" to "2024-01-16T14:20:00Z"
                ),
                mapOf(
                    "id" to "3",
                    "name" to "Charlie",
                    "active" to "true",
                    "created_at" to "2024-01-17T09:15:00Z"
                )
            )
        )

        tableFileManager.writeTable(table)
        val loaded = tableFileManager.readTable("test_table")

        assertNotNull(loaded)
        assertEquals("test_table", loaded?.tableName)
        assertEquals(3, loaded?.rows?.size)

        // First row
        assertEquals("1", loaded?.rows?.get(0)?.get("id"))
        assertEquals("Alice", loaded?.rows?.get(0)?.get("name"))
        assertEquals("true", loaded?.rows?.get(0)?.get("active"))
        assertEquals("2024-01-15T10:30:00Z", loaded?.rows?.get(0)?.get("created_at"))

        // Third row
        assertEquals("3", loaded?.rows?.get(2)?.get("id"))
        assertEquals("Charlie", loaded?.rows?.get(2)?.get("name"))
    }

    @Test
    @DisplayName("파일이 존재하지 않으면 null 반환")
    fun `returns null when file does not exist`() {
        val loaded = tableFileManager.readTable("non_existent")
        assertNull(loaded)
    }

    @Test
    @DisplayName("테이블 삭제")
    fun `deletes table file`() {
        val table = Table(
            tableName = "to_delete",
            dataType = mapOf("id" to "INT"),
            rows = listOf(mapOf("id" to "1"))
        )

        tableFileManager.writeTable(table)
        assertTrue(File(tempDir, "to_delete.dat").exists())

        val deleted = tableFileManager.deleteTable("to_delete")
        assertTrue(deleted)
        assertFalse(File(tempDir, "to_delete.dat").exists())
    }

    @Test
    @DisplayName("존재하지 않는 테이블 삭제는 false 반환")
    fun `returns false when deleting non-existent table`() {
        val deleted = tableFileManager.deleteTable("non_existent")
        assertFalse(deleted)
    }

    @Test
    @DisplayName("모든 테이블 목록 조회")
    fun `lists all tables`() {
        val table1 = Table("table1", mapOf("id" to "INT"), emptyList())
        val table2 = Table("table2", mapOf("name" to "VARCHAR"), emptyList())
        val table3 = Table("table3", mapOf("active" to "BOOLEAN"), emptyList())

        tableFileManager.writeTable(table1)
        tableFileManager.writeTable(table2)
        tableFileManager.writeTable(table3)

        val tables = tableFileManager.listAllTables()
        assertEquals(3, tables.size)
        assertTrue(tables.contains("table1"))
        assertTrue(tables.contains("table2"))
        assertTrue(tables.contains("table3"))
    }

    @Test
    @DisplayName("빈 디렉토리에서 테이블 목록은 빈 리스트")
    fun `returns empty list when directory is empty`() {
        val tables = tableFileManager.listAllTables()
        assertTrue(tables.isEmpty())
    }

    @Test
    @DisplayName("temp 파일은 목록에서 제외")
    fun `excludes temp files from listing`() {
        val table = Table("normal", mapOf("id" to "INT"), emptyList())
        tableFileManager.writeTable(table)

        // Create temp file manually
        File(tempDir, "temp_table.dat.tmp").createNewFile()

        val tables = tableFileManager.listAllTables()
        assertEquals(1, tables.size)
        assertEquals("normal", tables[0])
    }

    @Test
    @DisplayName("테이블 덮어쓰기")
    fun `overwrites existing table`() {
        val table1 = Table(
            "users",
            mapOf("id" to "INT"),
            listOf(mapOf("id" to "1"))
        )
        tableFileManager.writeTable(table1)

        val table2 = Table(
            "users",
            mapOf("id" to "INT"),
            listOf(mapOf("id" to "2"), mapOf("id" to "3"))
        )
        tableFileManager.writeTable(table2)

        val loaded = tableFileManager.readTable("users")
        assertEquals(2, loaded?.rows?.size)
        assertEquals("2", loaded?.rows?.get(0)?.get("id"))
    }

    @Test
    @DisplayName("스키마 순서 보존")
    fun `preserves schema order`() {
        val table = Table(
            tableName = "ordered",
            dataType = linkedMapOf(
                "first" to "VARCHAR",
                "second" to "INT",
                "third" to "BOOLEAN",
                "fourth" to "TIMESTAMP"
            ),
            rows = emptyList()
        )

        tableFileManager.writeTable(table)
        val loaded = tableFileManager.readTable("ordered")

        val schemaKeys = loaded?.dataType?.keys?.toList()
        assertEquals(listOf("first", "second", "third", "fourth"), schemaKeys)
    }

    @Test
    @DisplayName("특수 문자를 포함한 VARCHAR 처리")
    fun `handles VARCHAR with special characters`() {
        val table = Table(
            tableName = "special_chars",
            dataType = mapOf("text" to "VARCHAR"),
            rows = listOf(
                mapOf("text" to "Hello, World!"),
                mapOf("text" to "Line1\nLine2"),
                mapOf("text" to "Tab\tSeparated"),
                mapOf("text" to "Quote: \"test\""),
                mapOf("text" to "Unicode: 안녕하세요 こんにちは")
            )
        )

        tableFileManager.writeTable(table)
        val loaded = tableFileManager.readTable("special_chars")

        assertEquals(5, loaded?.rows?.size)
        assertEquals("Hello, World!", loaded?.rows?.get(0)?.get("text"))
        assertEquals("Unicode: 안녕하세요 こんにちは", loaded?.rows?.get(4)?.get("text"))
    }

    @Test
    @DisplayName("대용량 테이블 처리")
    fun `handles large table`() {
        val rows = (1..100).map { i ->
            mapOf(
                "id" to i.toString(),
                "name" to "User$i",
                "email" to "user$i@example.com"
            )
        }

        val table = Table(
            tableName = "large_table",
            dataType = mapOf("id" to "INT", "name" to "VARCHAR", "email" to "VARCHAR"),
            rows = rows
        )

        tableFileManager.writeTable(table)
        val loaded = tableFileManager.readTable("large_table")

        assertEquals(100, loaded?.rows?.size)
        assertEquals("50", loaded?.rows?.get(49)?.get("id"))
        assertEquals("User50", loaded?.rows?.get(49)?.get("name"))
    }

    @Test
    @DisplayName("파일이 생성되는지 확인")
    fun `verifies file is created`() {
        val table = Table("test", mapOf("id" to "INT"), emptyList())
        tableFileManager.writeTable(table)

        val file = File(tempDir, "test.dat")
        assertTrue(file.exists())
        assertTrue(file.isFile)
        assertTrue(file.length() > 0)
    }
}
