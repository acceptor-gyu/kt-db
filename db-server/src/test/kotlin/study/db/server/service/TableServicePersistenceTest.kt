package study.db.server.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import study.db.server.storage.*
import java.io.File

@DisplayName("TableService Persistence 통합 테스트")
class TableServicePersistenceTest {

    private lateinit var tempDir: File
    private lateinit var tableFileManager: TableFileManager
    private lateinit var tableService: TableService

    @BeforeEach
    fun setUp() {
        tempDir = createTempDir("table_service_persistence_test")
        val rowEncoder = RowEncoder(
            IntFieldEncoder(),
            VarcharFieldEncoder(),
            BooleanFieldEncoder(),
            TimestampFieldEncoder()
        )
        tableFileManager = TableFileManager(tempDir, rowEncoder)
        tableService = TableService(tableFileManager)
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    @DisplayName("CREATE TABLE 시 파일이 생성됨")
    fun `creates file when table is created`() {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))

        val file = File(tempDir, "users.dat")
        assertTrue(file.exists())
        assertTrue(file.isFile)
    }

    @Test
    @DisplayName("INSERT 시 데이터가 파일에 저장됨")
    fun `saves data to file on insert`() {
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
        tableService.insert("users", mapOf("id" to "1", "name" to "John"))
        tableService.insert("users", mapOf("id" to "2", "name" to "Jane"))

        // 파일에서 직접 읽어서 검증
        val loaded = tableFileManager.readTable("users")
        assertNotNull(loaded)
        assertEquals(2, loaded?.rows?.size)
        assertEquals("John", loaded?.rows?.get(0)?.get("name"))
        assertEquals("Jane", loaded?.rows?.get(1)?.get("name"))
    }

    @Test
    @DisplayName("서버 재시작 시 테이블이 복원됨")
    fun `restores tables on server restart`() {
        // 1. 테이블 생성 및 데이터 삽입
        tableService.createTable("products", mapOf(
            "id" to "INT",
            "name" to "VARCHAR",
            "price" to "INT",
            "available" to "BOOLEAN"
        ))
        tableService.insert("products", mapOf(
            "id" to "1",
            "name" to "Laptop",
            "price" to "1000",
            "available" to "true"
        ))
        tableService.insert("products", mapOf(
            "id" to "2",
            "name" to "Mouse",
            "price" to "20",
            "available" to "false"
        ))

        // 2. 서버 재시작 시뮬레이션 (TableService 재생성)
        val newTableService = TableService(tableFileManager)

        // 3. 데이터가 복원되었는지 확인
        val products = newTableService.select("products")
        assertNotNull(products)
        assertEquals(2, products?.rows?.size)
        assertEquals("Laptop", products?.rows?.get(0)?.get("name"))
        assertEquals("1000", products?.rows?.get(0)?.get("price"))
        assertEquals("Mouse", products?.rows?.get(1)?.get("name"))
        assertEquals("false", products?.rows?.get(1)?.get("available"))
    }

    @Test
    @DisplayName("DROP TABLE 시 파일이 삭제됨")
    fun `deletes file when table is dropped`() {
        tableService.createTable("temp_table", mapOf("id" to "INT"))
        val file = File(tempDir, "temp_table.dat")
        assertTrue(file.exists())

        tableService.dropTable("temp_table")
        assertFalse(file.exists())
    }

    @Test
    @DisplayName("여러 테이블이 독립적으로 persist됨")
    fun `persists multiple tables independently`() {
        // 3개의 테이블 생성
        tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
        tableService.createTable("posts", mapOf("id" to "INT", "title" to "VARCHAR"))
        tableService.createTable("comments", mapOf("id" to "INT", "content" to "VARCHAR"))

        // 각 테이블에 데이터 삽입
        tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
        tableService.insert("posts", mapOf("id" to "1", "title" to "First Post"))
        tableService.insert("comments", mapOf("id" to "1", "content" to "Great!"))

        // 서버 재시작
        val newTableService = TableService(tableFileManager)

        // 모든 테이블이 복원되었는지 확인
        assertEquals(3, newTableService.getAllTables().size)

        val users = newTableService.select("users")
        val posts = newTableService.select("posts")
        val comments = newTableService.select("comments")

        assertEquals("Alice", users?.rows?.last()?.get("name"))
        assertEquals("First Post", posts?.rows?.last()?.get("title"))
        assertEquals("Great!", comments?.rows?.last()?.get("content"))
    }

    @Test
    @DisplayName("모든 데이터 타입이 올바르게 persist됨")
    fun `persists all data types correctly`() {
        tableService.createTable("test_all_types", mapOf(
            "id" to "INT",
            "name" to "VARCHAR",
            "active" to "BOOLEAN",
            "created_at" to "TIMESTAMP"
        ))

        tableService.insert("test_all_types", mapOf(
            "id" to "123",
            "name" to "Test Entry",
            "active" to "true",
            "created_at" to "2024-01-15T10:30:00Z"
        ))

        // 서버 재시작
        val newTableService = TableService(tableFileManager)

        val table = newTableService.select("test_all_types")
        assertEquals("123", table?.rows?.last()?.get("id"))
        assertEquals("Test Entry", table?.rows?.last()?.get("name"))
        assertEquals("true", table?.rows?.last()?.get("active"))
        assertEquals("2024-01-15T10:30:00Z", table?.rows?.last()?.get("created_at"))
    }

    @Test
    @DisplayName("빈 테이블도 persist됨")
    fun `persists empty table`() {
        tableService.createTable("empty", mapOf("id" to "INT"))

        val file = File(tempDir, "empty.dat")
        assertTrue(file.exists())

        // 서버 재시작
        val newTableService = TableService(tableFileManager)

        val table = newTableService.select("empty")
        assertNotNull(table)
        assertEquals("empty", table?.tableName)
        assertEquals(0, table?.rows?.size)
    }

    @Test
    @DisplayName("대용량 데이터 persist")
    fun `persists large dataset`() {
        tableService.createTable("large_table", mapOf(
            "id" to "INT",
            "email" to "VARCHAR"
        ))

        // 100개 행 삽입
        for (i in 1..100) {
            tableService.insert("large_table", mapOf(
                "id" to i.toString(),
                "email" to "user$i@example.com"
            ))
        }

        // 서버 재시작
        val newTableService = TableService(tableFileManager)

        val table = newTableService.select("large_table")
        assertEquals(100, table?.rows?.size)
        assertEquals("user50@example.com", table?.rows?.get(49)?.get("email"))
        assertEquals("user100@example.com", table?.rows?.get(99)?.get("email"))
    }

    @Test
    @DisplayName("테이블 업데이트가 파일에 반영됨")
    fun `updates file when table is modified`() {
        tableService.createTable("update_test", mapOf("id" to "INT"))
        tableService.insert("update_test", mapOf("id" to "1"))

        // 첫 번째 재시작
        var newTableService = TableService(tableFileManager)
        assertEquals(1, newTableService.select("update_test")?.rows?.size)

        // 추가 데이터 삽입
        newTableService.insert("update_test", mapOf("id" to "2"))
        newTableService.insert("update_test", mapOf("id" to "3"))

        // 두 번째 재시작
        newTableService = TableService(tableFileManager)
        assertEquals(3, newTableService.select("update_test")?.rows?.size)
    }

    @Test
    @DisplayName("손상된 파일이 있어도 다른 테이블은 로드됨")
    fun `loads other tables even if one file is corrupted`() {
        tableService.createTable("valid1", mapOf("id" to "INT"))
        tableService.createTable("valid2", mapOf("id" to "INT"))
        tableService.insert("valid1", mapOf("id" to "1"))
        tableService.insert("valid2", mapOf("id" to "2"))

        // 하나의 파일을 손상시킴
        File(tempDir, "valid1.dat").writeBytes(ByteArray(10) { 0xFF.toByte() })

        // 서버 재시작 - 손상된 파일은 로드 실패하지만 다른 파일은 로드됨
        val newTableService = TableService(tableFileManager)

        // valid2는 정상 로드되어야 함
        val valid2 = newTableService.select("valid2")
        assertNotNull(valid2)
        assertEquals("2", valid2?.rows?.last()?.get("id"))
    }

    @Test
    @DisplayName("persistence 없이도 동작함 (optional)")
    fun `works without persistence`() {
        val serviceWithoutPersistence = TableService(null)

        serviceWithoutPersistence.createTable("temp", mapOf("id" to "INT"))
        serviceWithoutPersistence.insert("temp", mapOf("id" to "1"))

        val table = serviceWithoutPersistence.select("temp")
        assertEquals("1", table?.rows?.last()?.get("id"))

        // 파일이 생성되지 않아야 함
        assertFalse(File(tempDir, "temp.dat").exists())
    }
}
