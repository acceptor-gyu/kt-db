package study.db.server.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import study.db.server.exception.ColumnNotFoundException
import study.db.server.exception.TypeMismatchException
import study.db.server.exception.UnsupportedTypeException

/**
 * TableService 테스트
 *
 * TableService는 인메모리 DB의 핵심 비즈니스 로직을 담당합니다.
 * 테이블 생성, 조회, 삽입, 삭제 등의 CRUD 작업을 처리합니다.
 */
@DisplayName("TableService 테스트")
class TableServiceTest {

    private lateinit var tableService: TableService

    /**
     * 각 테스트 전에 새로운 TableService 인스턴스를 생성합니다.
     */
    @BeforeEach
    fun setup() {
        tableService = TableService()
    }

    @Nested
    @DisplayName("테이블 생성 테스트")
    inner class CreateTableTest {

        @Test
        @DisplayName("기본 테이블 생성")
        fun `creates table with columns`() {
            // Given: 테이블 이름과 컬럼 정의
            val tableName = "users"
            val columns = mapOf(
                "id" to "INT",
                "name" to "VARCHAR"
            )

            // When: 테이블 생성
            val query = tableService.createTable(tableName, columns)

            // Then: 테이블이 생성되고 CREATE TABLE 쿼리 반환
            assertTrue(tableService.tableExists(tableName))
            assertTrue(query.contains("CREATE TABLE"))
            assertTrue(query.contains(tableName))
            assertTrue(query.contains("id INT"))
            assertTrue(query.contains("name VARCHAR"))
        }

        @Test
        @DisplayName("여러 컬럼을 가진 테이블 생성")
        fun `creates table with multiple columns`() {
            // Given: 여러 컬럼을 가진 테이블 정의
            val columns = mapOf(
                "id" to "INT",
                "username" to "VARCHAR",
                "email" to "VARCHAR",
                "age" to "INT",
                "created_at" to "TIMESTAMP"
            )

            // When: 테이블 생성
            val query = tableService.createTable("users", columns)

            // Then: 모든 컬럼이 쿼리에 포함됨
            assertNotNull(query)
            columns.forEach { (name, type) ->
                assertTrue(query.contains(name))
                assertTrue(query.contains(type))
            }
        }

        @Test
        @DisplayName("여러 테이블 생성")
        fun `creates multiple tables`() {
            // Given: 두 개의 테이블 정의
            val usersColumns = mapOf("id" to "INT", "name" to "VARCHAR")
            val postsColumns = mapOf("id" to "INT", "title" to "VARCHAR")

            // When: 두 테이블 생성
            tableService.createTable("users", usersColumns)
            tableService.createTable("posts", postsColumns)

            // Then: 두 테이블 모두 존재
            assertTrue(tableService.tableExists("users"))
            assertTrue(tableService.tableExists("posts"))
            assertEquals(2, tableService.getAllTables().size)
        }

        @Test
        @DisplayName("빈 컬럼으로 테이블 생성")
        fun `creates table with empty columns`() {
            // Given: 빈 컬럼 맵
            val emptyColumns = emptyMap<String, String>()

            // When: 테이블 생성
            val query = tableService.createTable("empty_table", emptyColumns)

            // Then: 테이블은 생성되지만 컬럼이 없음
            assertTrue(tableService.tableExists("empty_table"))
            assertTrue(query.contains("CREATE TABLE empty_table"))
        }
    }

    @Nested
    @DisplayName("데이터 삽입 테스트")
    inner class InsertTest {

        @BeforeEach
        fun setupTable() {
            // 테스트용 테이블 생성
            tableService.createTable("users", mapOf(
                "id" to "INT",
                "name" to "VARCHAR"
            ))
        }

        @Test
        @DisplayName("테이블에 데이터 삽입")
        fun `inserts data into table`() {
            // Given: 삽입할 데이터
            val values = mapOf(
                "id" to "1",
                "name" to "John"
            )

            // When: 데이터 삽입
            assertDoesNotThrow { tableService.insert("users", values) }

            // Then: 데이터 확인
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals(values, table?.rows?.last())
        }

        @Test
        @DisplayName("존재하지 않는 테이블에 삽입 실패")
        fun `fails to insert into non-existent table`() {
            // Given: 존재하지 않는 테이블
            val values = mapOf("id" to "1")

            // When & Then: 존재하지 않는 테이블에 삽입 시도 시 예외 발생
            assertThrows<IllegalStateException> {
                tableService.insert("non_existent", values)
            }
        }

        @Test
        @DisplayName("여러 번 삽입하면 모든 행이 저장됨")
        fun `multiple inserts store all rows`() {
            // Given: 초기 데이터
            val initialValues = mapOf("id" to "1", "name" to "John")
            tableService.insert("users", initialValues)

            // When: 추가 데이터 삽입
            val additionalValues = mapOf("id" to "2", "name" to "Jane")
            tableService.insert("users", additionalValues)

            // Then: 두 행 모두 저장됨
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals(2, table?.rows?.size)
            assertEquals("John", table?.rows?.get(0)?.get("name"))
            assertEquals("Jane", table?.rows?.get(1)?.get("name"))
        }

        @Test
        @DisplayName("빈 값 삽입")
        fun `inserts empty values`() {
            // Given: 빈 값
            val emptyValues = emptyMap<String, String>()

            // When: 빈 값 삽입
            assertDoesNotThrow { tableService.insert("users", emptyValues) }

            // Then: 빈 맵이 한 행으로 저장됨
            val table = tableService.select("users")
            assertEquals(1, table?.rows?.size)
            assertTrue(table?.rows?.first()?.isEmpty() ?: false)
        }
    }

    @Nested
    @DisplayName("테이블 조회 테스트")
    inner class SelectTest {

        @Test
        @DisplayName("존재하는 테이블 조회")
        fun `selects existing table`() {
            // Given: 테이블 생성
            val columns = mapOf("id" to "INT", "name" to "VARCHAR")
            tableService.createTable("users", columns)

            // When: 테이블 조회
            val table = tableService.select("users")

            // Then: 테이블 정보 반환
            assertNotNull(table)
            assertEquals("users", table?.tableName)
            assertEquals(columns, table?.dataType)
        }

        @Test
        @DisplayName("존재하지 않는 테이블 조회 시 null 반환")
        fun `returns null for non-existent table`() {
            // When: 존재하지 않는 테이블 조회
            val table = tableService.select("non_existent")

            // Then: null 반환
            assertNull(table)
        }

        @Test
        @DisplayName("데이터가 있는 테이블 조회")
        fun `selects table with data`() {
            // Given: 데이터가 있는 테이블
            tableService.createTable("users", mapOf("id" to "INT"))
            val values = mapOf("id" to "1")
            tableService.insert("users", values)

            // When: 테이블 조회
            val table = tableService.select("users")

            // Then: 데이터 포함하여 반환
            assertNotNull(table)
            assertEquals(values, table?.rows?.last())
        }
    }

    @Nested
    @DisplayName("전체 테이블 조회 테스트")
    inner class GetAllTablesTest {

        @Test
        @DisplayName("모든 테이블 조회")
        fun `gets all tables`() {
            // Given: 여러 테이블 생성
            tableService.createTable("users", mapOf("id" to "INT"))
            tableService.createTable("posts", mapOf("id" to "INT"))
            tableService.createTable("comments", mapOf("id" to "INT"))

            // When: 모든 테이블 조회
            val allTables = tableService.getAllTables()

            // Then: 3개의 테이블 반환
            assertEquals(3, allTables.size)
            assertTrue(allTables.any { it.tableName == "users" })
            assertTrue(allTables.any { it.tableName == "posts" })
            assertTrue(allTables.any { it.tableName == "comments" })
        }

        @Test
        @DisplayName("테이블이 없을 때 빈 리스트 반환")
        fun `returns empty list when no tables exist`() {
            // When: 테이블이 없을 때 조회
            val allTables = tableService.getAllTables()

            // Then: 빈 리스트 반환
            assertTrue(allTables.isEmpty())
        }
    }

    @Nested
    @DisplayName("테이블 삭제 테스트")
    inner class DropTableTest {

        @Test
        @DisplayName("존재하는 테이블 삭제")
        fun `drops existing table`() {
            // Given: 테이블 생성
            tableService.createTable("users", mapOf("id" to "INT"))
            assertTrue(tableService.tableExists("users"))

            // When: 테이블 삭제
            val result = tableService.dropTable("users")

            // Then: 삭제 성공 및 테이블 존재하지 않음
            assertTrue(result)
            assertFalse(tableService.tableExists("users"))
        }

        @Test
        @DisplayName("존재하지 않는 테이블 삭제 실패")
        fun `fails to drop non-existent table`() {
            // When: 존재하지 않는 테이블 삭제 시도
            val result = tableService.dropTable("non_existent")

            // Then: 삭제 실패
            assertFalse(result)
        }

        @Test
        @DisplayName("데이터가 있는 테이블도 삭제 가능")
        fun `drops table with data`() {
            // Given: 데이터가 있는 테이블
            tableService.createTable("users", mapOf("id" to "INT"))
            tableService.insert("users", mapOf("id" to "1"))

            // When: 테이블 삭제
            val result = tableService.dropTable("users")

            // Then: 삭제 성공
            assertTrue(result)
            assertNull(tableService.select("users"))
        }

        @Test
        @DisplayName("테이블 삭제 후 재생성 가능")
        fun `can recreate table after dropping`() {
            // Given: 테이블 생성 및 삭제
            tableService.createTable("users", mapOf("id" to "INT"))
            tableService.dropTable("users")

            // When: 동일한 이름으로 테이블 재생성
            tableService.createTable("users", mapOf("name" to "VARCHAR"))

            // Then: 새로운 구조로 테이블 생성됨
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals(mapOf("name" to "VARCHAR"), table?.dataType)
        }
    }

    @Nested
    @DisplayName("테이블 존재 확인 테스트")
    inner class TableExistsTest {

        @Test
        @DisplayName("존재하는 테이블 확인")
        fun `checks existing table`() {
            // Given: 테이블 생성
            tableService.createTable("users", mapOf("id" to "INT"))

            // When & Then: 테이블 존재 확인
            assertTrue(tableService.tableExists("users"))
        }

        @Test
        @DisplayName("존재하지 않는 테이블 확인")
        fun `checks non-existent table`() {
            // When & Then: 테이블 미존재 확인
            assertFalse(tableService.tableExists("non_existent"))
        }

        @Test
        @DisplayName("삭제된 테이블은 존재하지 않음")
        fun `deleted table does not exist`() {
            // Given: 테이블 생성 및 삭제
            tableService.createTable("users", mapOf("id" to "INT"))
            tableService.dropTable("users")

            // When & Then: 삭제된 테이블은 존재하지 않음
            assertFalse(tableService.tableExists("users"))
        }
    }

    @Nested
    @DisplayName("통합 시나리오 테스트")
    inner class IntegrationTest {

        @Test
        @DisplayName("전체 CRUD 작업 시나리오")
        fun `performs full CRUD operations`() {
            // Create
            val columns = mapOf("id" to "INT", "name" to "VARCHAR", "email" to "VARCHAR")
            tableService.createTable("users", columns)
            assertTrue(tableService.tableExists("users"))

            // Insert
            val userData = mapOf("id" to "1", "name" to "John", "email" to "john@example.com")
            assertDoesNotThrow { tableService.insert("users", userData) }

            // Read
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals("users", table?.tableName)
            assertEquals(userData, table?.rows?.last())

            // Update (재삽입)
            val updatedData = mapOf("id" to "1", "name" to "John Doe")
            assertDoesNotThrow { tableService.insert("users", updatedData) }

            // Delete
            assertTrue(tableService.dropTable("users"))
            assertFalse(tableService.tableExists("users"))
        }

        @Test
        @DisplayName("여러 테이블 동시 관리")
        fun `manages multiple tables concurrently`() {
            // Given: 여러 테이블 생성
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
            tableService.createTable("posts", mapOf("id" to "INT", "title" to "VARCHAR"))
            tableService.createTable("comments", mapOf("id" to "INT", "content" to "VARCHAR"))

            // When: 각 테이블에 데이터 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "John"))
            tableService.insert("posts", mapOf("id" to "1", "title" to "First Post"))
            tableService.insert("comments", mapOf("id" to "1", "content" to "Great!"))

            // Then: 모든 테이블 독립적으로 존재
            assertEquals(3, tableService.getAllTables().size)

            val users = tableService.select("users")
            val posts = tableService.select("posts")
            val comments = tableService.select("comments")

            assertNotNull(users)
            assertNotNull(posts)
            assertNotNull(comments)

            assertEquals("John", users?.rows?.last()?.get("name"))
            assertEquals("First Post", posts?.rows?.last()?.get("title"))
            assertEquals("Great!", comments?.rows?.last()?.get("content"))
        }
    }

    @Nested
    @DisplayName("타입 검증 테스트")
    inner class TypeValidationTest {

        @BeforeEach
        fun setupTable() {
            tableService.createTable("users", mapOf(
                "id" to "INT",
                "name" to "VARCHAR",
                "email" to "VARCHAR",
                "age" to "INT",
                "active" to "BOOLEAN",
                "created_at" to "TIMESTAMP"
            ))
        }

        @Test
        @DisplayName("INT 타입 검증 - 정상")
        fun `validates INT type successfully`() {
            val values = mapOf("id" to "123", "age" to "25")
            assertDoesNotThrow { tableService.insert("users", values) }
        }

        @Test
        @DisplayName("INT 타입 검증 - 실패 (문자열)")
        fun `fails INT validation with string value`() {
            val values = mapOf("id" to "abc")
            val exception = assertThrows<TypeMismatchException> {
                tableService.insert("users", values)
            }
            assertEquals("abc", exception.value)
            assertEquals("INT", exception.expectedType)
        }

        @Test
        @DisplayName("INT 타입 검증 - 실패 (소수점)")
        fun `fails INT validation with decimal value`() {
            val values = mapOf("age" to "25.5")
            assertThrows<TypeMismatchException> {
                tableService.insert("users", values)
            }
        }

        @Test
        @DisplayName("VARCHAR 타입 검증 - 항상 성공")
        fun `validates VARCHAR type always succeeds`() {
            val values = mapOf(
                "name" to "John Doe",
                "email" to "john@example.com"
            )
            assertDoesNotThrow { tableService.insert("users", values) }
        }

        @Test
        @DisplayName("BOOLEAN 타입 검증 - 정상")
        fun `validates BOOLEAN type successfully`() {
            val values1 = mapOf("active" to "true")
            assertDoesNotThrow { tableService.insert("users", values1) }

            val values2 = mapOf("active" to "false")
            assertDoesNotThrow { tableService.insert("users", values2) }

            val values3 = mapOf("active" to "TRUE")
            assertDoesNotThrow { tableService.insert("users", values3) }
        }

        @Test
        @DisplayName("BOOLEAN 타입 검증 - 실패")
        fun `fails BOOLEAN validation with invalid value`() {
            val values = mapOf("active" to "yes")
            val exception = assertThrows<TypeMismatchException> {
                tableService.insert("users", values)
            }
            assertTrue(exception.message!!.contains("true' or 'false"))
        }

        @Test
        @DisplayName("TIMESTAMP 타입 검증 - 정상 (ISO-8601)")
        fun `validates TIMESTAMP type successfully`() {
            val values = mapOf("created_at" to "2024-01-15T10:30:00Z")
            assertDoesNotThrow { tableService.insert("users", values) }
        }

        @Test
        @DisplayName("TIMESTAMP 타입 검증 - 정상 (LocalDateTime)")
        fun `validates TIMESTAMP with LocalDateTime format`() {
            val values = mapOf("created_at" to "2024-01-15 10:30:00")
            assertDoesNotThrow { tableService.insert("users", values) }
        }

        @Test
        @DisplayName("TIMESTAMP 타입 검증 - 실패")
        fun `fails TIMESTAMP validation with invalid format`() {
            val values = mapOf("created_at" to "2024/01/15")
            assertThrows<TypeMismatchException> {
                tableService.insert("users", values)
            }
        }

        @Test
        @DisplayName("여러 타입 동시 검증 - 정상")
        fun `validates multiple types successfully`() {
            val values = mapOf(
                "id" to "1",
                "name" to "Alice",
                "email" to "alice@example.com",
                "age" to "30",
                "active" to "true",
                "created_at" to "2024-01-15T10:30:00Z"
            )
            assertDoesNotThrow { tableService.insert("users", values) }
        }
    }

    @Nested
    @DisplayName("컬럼 존재 여부 검증 테스트")
    inner class ColumnExistenceTest {

        @BeforeEach
        fun setupTable() {
            tableService.createTable("users", mapOf(
                "id" to "INT",
                "name" to "VARCHAR"
            ))
        }

        @Test
        @DisplayName("정의된 컬럼 삽입 - 정상")
        fun `inserts with defined columns successfully`() {
            val values = mapOf("id" to "1", "name" to "John")
            assertDoesNotThrow { tableService.insert("users", values) }
        }

        @Test
        @DisplayName("정의되지 않은 컬럼 삽입 - 실패")
        fun `fails to insert undefined column`() {
            val values = mapOf("id" to "1", "email" to "john@example.com")
            val exception = assertThrows<ColumnNotFoundException> {
                tableService.insert("users", values)
            }
            assertEquals("users", exception.tableName)
            assertEquals("email", exception.columnName)
        }

        @Test
        @DisplayName("일부 정의되지 않은 컬럼 - 실패")
        fun `fails when one column is undefined`() {
            val values = mapOf(
                "id" to "1",
                "name" to "John",
                "age" to "25"  // 정의되지 않음
            )
            assertThrows<ColumnNotFoundException> {
                tableService.insert("users", values)
            }
        }

        @Test
        @DisplayName("빈 값 삽입 - 정상")
        fun `allows empty values`() {
            val values = emptyMap<String, String>()
            assertDoesNotThrow { tableService.insert("users", values) }
        }
    }

    @Nested
    @DisplayName("엣지 케이스 및 통합 테스트")
    inner class EdgeCasesTest {

        @Test
        @DisplayName("존재하지 않는 테이블 - 실패")
        fun `fails when table does not exist`() {
            val values = mapOf("id" to "1")
            assertThrows<IllegalStateException> {
                tableService.insert("non_existent", values)
            }
        }

        @Test
        @DisplayName("INT 범위 최대값 - 정상")
        fun `accepts INT max value`() {
            tableService.createTable("test", mapOf("num" to "INT"))
            val values = mapOf("num" to "2147483647")
            assertDoesNotThrow { tableService.insert("test", values) }
        }

        @Test
        @DisplayName("INT 범위 최소값 - 정상")
        fun `accepts INT min value`() {
            tableService.createTable("test", mapOf("num" to "INT"))
            val values = mapOf("num" to "-2147483648")
            assertDoesNotThrow { tableService.insert("test", values) }
        }

        @Test
        @DisplayName("INT 범위 초과 - 실패")
        fun `fails when INT value exceeds range`() {
            tableService.createTable("test", mapOf("num" to "INT"))
            val values = mapOf("num" to "2147483648")
            assertThrows<TypeMismatchException> {
                tableService.insert("test", values)
            }
        }

        @Test
        @DisplayName("지원하지 않는 타입 - 실패")
        fun `fails with unsupported type`() {
            tableService.createTable("test", mapOf("data" to "BLOB"))
            val values = mapOf("data" to "binary_data")
            assertThrows<UnsupportedTypeException> {
                tableService.insert("test", values)
            }
        }

        @Test
        @DisplayName("대소문자 무관 타입 검증")
        fun `validates types case insensitively`() {
            tableService.createTable("test", mapOf(
                "id" to "int",
                "name" to "varchar",
                "active" to "boolean"
            ))
            val values = mapOf(
                "id" to "1",
                "name" to "Test",
                "active" to "true"
            )
            assertDoesNotThrow { tableService.insert("test", values) }
        }
    }
}
