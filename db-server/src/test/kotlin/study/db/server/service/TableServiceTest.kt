package study.db.server.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import study.db.server.db_engine.dto.OrderByColumn
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

    @Nested
    @DisplayName("데이터 삭제 테스트")
    inner class DeleteTest {

        @BeforeEach
        fun setupTable() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
        }

        @Test
        @DisplayName("WHERE 조건에 맞는 행만 삭제")
        fun `deletes rows matching WHERE condition`() {
            // Given: 3개의 행 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Charlie"))

            // When: id=2인 행 삭제
            val deletedCount = tableService.delete("users", "id=2")

            // Then: 1개 행 삭제됨
            assertEquals(1, deletedCount)
        }

        @Test
        @DisplayName("모든 행 삭제 (WHERE 절 없음)")
        fun `deletes all rows when no WHERE clause`() {
            // Given: 3개의 행 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Charlie"))

            // When: WHERE 절 없이 삭제
            val deletedCount = tableService.delete("users", null)

            // Then: 3개 행 삭제됨
            assertEquals(3, deletedCount)
        }

        @Test
        @DisplayName("삭제된 행 개수 반환")
        fun `returns count of deleted rows`() {
            // Given: 여러 행 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))

            // When: 삭제
            val deletedCount = tableService.delete("users", "name='Alice'")

            // Then: 삭제된 행 개수 반환
            assertEquals(1, deletedCount)
        }

        @Test
        @DisplayName("존재하지 않는 테이블 삭제 시 예외")
        fun `throws exception when table does not exist`() {
            // When & Then: 존재하지 않는 테이블 삭제 시 예외
            assertThrows<IllegalStateException> {
                tableService.delete("nonexistent", null)
            }
        }

        @Test
        @DisplayName("조건에 맞는 행이 없으면 0 반환")
        fun `returns zero when no rows match condition`() {
            // Given: 행 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))

            // When: 조건에 맞지 않는 행 삭제
            val deletedCount = tableService.delete("users", "id=999")

            // Then: 0 반환
            assertEquals(0, deletedCount)
        }

        @Test
        @DisplayName("잘못된 WHERE 구문 시 예외")
        fun `throws exception for invalid WHERE clause`() {
            // When & Then: 잘못된 WHERE 구문
            assertThrows<IllegalArgumentException> {
                tableService.delete("users", "invalid syntax here")
            }
        }

        @Test
        @DisplayName("작은따옴표와 큰따옴표 모두 지원")
        fun `supports both single and double quotes in WHERE`() {
            // Given: 데이터 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))

            // When: 작은따옴표로 삭제
            val count1 = tableService.delete("users", "name='Alice'")
            // When: 큰따옴표로 삭제
            val count2 = tableService.delete("users", "name=\"Bob\"")

            // Then: 둘 다 성공
            assertEquals(1, count1)
            assertEquals(1, count2)
        }

        @Test
        @DisplayName("따옴표 없는 값도 지원 (숫자)")
        fun `supports values without quotes for numbers`() {
            // Given: 데이터 삽입
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))

            // When: 따옴표 없이 삭제
            val deletedCount = tableService.delete("users", "id=1")

            // Then: 성공
            assertEquals(1, deletedCount)
        }
    }

    @Nested
    @DisplayName("복합 WHERE 조건 DELETE 테스트")
    inner class ComplexWhereDeleteTest {

        @BeforeEach
        fun setupTable() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice", "age" to "30"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob", "age" to "25"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Charlie", "age" to "35"))
        }

        @Test
        @DisplayName("AND 조건 DELETE - 두 조건 모두 만족하는 행만 삭제")
        fun `deletes rows matching AND condition`() {
            // When: age > 28 AND name='Alice'
            val deletedCount = tableService.delete("users", "age > 28 AND name='Alice'")

            // Then: Alice만 삭제
            assertEquals(1, deletedCount)
            val result = tableService.select("users")
            assertEquals(2, result?.rows?.size)
            assertTrue(result?.rows?.any { it["name"] == "Bob" } ?: false)
            assertTrue(result?.rows?.any { it["name"] == "Charlie" } ?: false)
        }

        @Test
        @DisplayName("OR 조건 DELETE - 하나라도 만족하는 행 삭제")
        fun `deletes rows matching OR condition`() {
            // When: id=1 OR id=3
            val deletedCount = tableService.delete("users", "id=1 OR id=3")

            // Then: Alice, Charlie 삭제, Bob만 유지
            assertEquals(2, deletedCount)
            val result = tableService.select("users")
            assertEquals(1, result?.rows?.size)
            assertEquals("Bob", result?.rows?.first()?.get("name"))
        }

        @Test
        @DisplayName("비교 연산자 > DELETE")
        fun `deletes rows with greater than operator`() {
            // When: id > 2
            val deletedCount = tableService.delete("users", "id > 2")

            // Then: id=3만 삭제
            assertEquals(1, deletedCount)
        }

        @Test
        @DisplayName("비교 연산자 < DELETE")
        fun `deletes rows with less than operator`() {
            // When: id < 2
            val deletedCount = tableService.delete("users", "id < 2")

            // Then: id=1만 삭제
            assertEquals(1, deletedCount)
        }

        @Test
        @DisplayName("비교 연산자 >= DELETE")
        fun `deletes rows with greater than or equal operator`() {
            // When: id >= 2
            val deletedCount = tableService.delete("users", "id >= 2")

            // Then: id=2, 3 삭제
            assertEquals(2, deletedCount)
        }

        @Test
        @DisplayName("비교 연산자 <= DELETE")
        fun `deletes rows with less than or equal operator`() {
            // When: id <= 2
            val deletedCount = tableService.delete("users", "id <= 2")

            // Then: id=1, 2 삭제
            assertEquals(2, deletedCount)
        }

        @Test
        @DisplayName("비교 연산자 != DELETE")
        fun `deletes rows with not equal operator`() {
            // When: name != 'Alice'
            val deletedCount = tableService.delete("users", "name != 'Alice'")

            // Then: Bob, Charlie 삭제
            assertEquals(2, deletedCount)
            val result = tableService.select("users")
            assertEquals(1, result?.rows?.size)
            assertEquals("Alice", result?.rows?.first()?.get("name"))
        }

        @Test
        @DisplayName("INT 타입 숫자 비교 DELETE")
        fun `deletes rows with numeric comparison for INT type`() {
            // Given: age가 3, 25, 100인 데이터로 재구성
            tableService.dropTable("users")
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice", "age" to "3"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob", "age" to "25"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Charlie", "age" to "100"))

            // When: age > 25 (INT 비교이므로 3 < 25, 문자열이면 "3" > "25")
            val deletedCount = tableService.delete("users", "age > 25")

            // Then: Charlie만 삭제 (INT 비교 기준)
            assertEquals(1, deletedCount)
            val result = tableService.select("users")
            assertEquals(2, result?.rows?.size)
            assertTrue(result?.rows?.any { it["name"] == "Alice" } ?: false)
            assertTrue(result?.rows?.any { it["name"] == "Bob" } ?: false)
        }

        @Test
        @DisplayName("VARCHAR 타입 문자열 비교 DELETE")
        fun `deletes rows with string comparison for VARCHAR type`() {
            // When: name > 'B' (사전순 비교)
            val deletedCount = tableService.delete("users", "name > 'B'")

            // Then: Bob, Charlie 삭제 (사전순으로 "Bob" > "B", "Charlie" > "B")
            assertEquals(2, deletedCount)
            val result = tableService.select("users")
            assertEquals(1, result?.rows?.size)
            assertEquals("Alice", result?.rows?.first()?.get("name"))
        }

        @Test
        @DisplayName("존재하지 않는 컬럼 WHERE 조건 시 예외")
        fun `throws exception for non-existent column in WHERE`() {
            // When & Then: 존재하지 않는 컬럼으로 삭제 시도
            assertThrows<ColumnNotFoundException> {
                tableService.delete("users", "email='test'")
            }
        }

        @Test
        @DisplayName("AND 조건으로 매칭되는 행이 없을 때 0 반환")
        fun `returns zero when no rows match AND condition`() {
            // When: id=1이면서 name=Bob인 행 없음
            val deletedCount = tableService.delete("users", "id=1 AND name='Bob'")

            // Then: 0 반환
            assertEquals(0, deletedCount)
        }
    }

    @Nested
    @DisplayName("SELECT WHERE 필터링 테스트")
    inner class SelectWhereTest {

        @BeforeEach
        fun setUp() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice", "age" to "30"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob", "age" to "25"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Charlie", "age" to "35"))
        }

        @Test
        @DisplayName("등호 조건 WHERE 필터링")
        fun `등호 조건 WHERE 필터링`() {
            val result = tableService.select("users", whereString = "name='Alice'")
            assertNotNull(result)
            assertEquals(1, result!!.rows.size)
            assertEquals("Alice", result.rows[0]["name"])
        }

        @Test
        @DisplayName("비교 연산자 WHERE 필터링")
        fun `비교 연산자 WHERE 필터링`() {
            val result = tableService.select("users", whereString = "age > 28")
            assertNotNull(result)
            assertEquals(2, result!!.rows.size)  // Alice(30), Charlie(35)
        }

        @Test
        @DisplayName("AND 조건 WHERE 필터링")
        fun `AND 조건 WHERE 필터링`() {
            val result = tableService.select("users", whereString = "age > 28 AND name='Alice'")
            assertNotNull(result)
            assertEquals(1, result!!.rows.size)
            assertEquals("Alice", result.rows[0]["name"])
        }

        @Test
        @DisplayName("OR 조건 WHERE 필터링")
        fun `OR 조건 WHERE 필터링`() {
            val result = tableService.select("users", whereString = "id=1 OR id=3")
            assertNotNull(result)
            assertEquals(2, result!!.rows.size)
        }

        @Test
        @DisplayName("INT 숫자 비교 (사전순 아닌 숫자순)")
        fun `INT 숫자 비교 (사전순 아닌 숫자순)`() {
            // age: 3, 25, 100 → 사전순이면 "100" < "25" < "3", 숫자순이면 3 < 25 < 100
            tableService.createTable("nums", mapOf("age" to "INT"))
            tableService.insert("nums", mapOf("age" to "3"))
            tableService.insert("nums", mapOf("age" to "100"))
            tableService.insert("nums", mapOf("age" to "25"))
            val result = tableService.select("nums", whereString = "age > 25")
            assertNotNull(result)
            assertEquals(1, result!!.rows.size)
            assertEquals("100", result.rows[0]["age"])
        }

        @Test
        @DisplayName("WHERE 없음 - 전체 반환")
        fun `WHERE 없음 - 전체 반환`() {
            val result = tableService.select("users")
            assertNotNull(result)
            assertEquals(3, result!!.rows.size)
        }

        @Test
        @DisplayName("존재하지 않는 컬럼 WHERE - 예외 발생")
        fun `존재하지 않는 컬럼 WHERE - 예외 발생`() {
            assertThrows<ColumnNotFoundException> {
                tableService.select("users", whereString = "email='test'")
            }
        }

        @Test
        @DisplayName("조건 만족 행 없음 - 빈 rows 반환")
        fun `조건 만족 행 없음 - 빈 rows 반환`() {
            val result = tableService.select("users", whereString = "id=999")
            assertNotNull(result)
            assertEquals(0, result!!.rows.size)
        }
    }

    @Nested
    @DisplayName("SELECT 컬럼 프로젝션 테스트")
    inner class SelectColumnProjectionTest {

        @BeforeEach
        fun setUp() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice", "age" to "30"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob", "age" to "25"))
        }

        @Test
        @DisplayName("특정 컬럼만 SELECT")
        fun `특정 컬럼만 SELECT`() {
            val result = tableService.select("users", columns = listOf("name"))
            assertThat(result!!.rows[0].keys).containsExactly("name")
            assertThat(result.dataType.keys).containsExactly("name")
        }

        @Test
        @DisplayName("SELECT star - 전체 컬럼 반환")
        fun `SELECT star - 전체 컬럼 반환`() {
            val result = tableService.select("users", columns = listOf("*"))
            assertThat(result!!.rows[0].keys).containsExactlyInAnyOrder("id", "name", "age")
        }

        @Test
        @DisplayName("존재하지 않는 컬럼 SELECT - 예외 발생")
        fun `존재하지 않는 컬럼 SELECT - 예외 발생`() {
            assertThrows<ColumnNotFoundException> {
                tableService.select("users", columns = listOf("email"))
            }
        }

        @Test
        @DisplayName("WHERE 컬럼과 SELECT 컬럼이 다른 경우 (age로 필터, name만 SELECT)")
        fun `WHERE 컬럼과 SELECT 컬럼이 다른 경우 (age로 필터, name만 SELECT)`() {
            val result = tableService.select("users", whereString = "age > 28", columns = listOf("name"))
            assertThat(result!!.rows).hasSize(1)
            assertThat(result.rows[0].keys).containsExactly("name")
            assertThat(result.rows[0]["age"]).isNull()
        }

        @Test
        @DisplayName("dataType도 함께 프로젝션되는지 확인")
        fun `dataType도 함께 프로젝션되는지 확인`() {
            val result = tableService.select("users", columns = listOf("id", "name"))
            assertThat(result!!.dataType).isEqualTo(mapOf("id" to "INT", "name" to "VARCHAR"))
        }
    }

    @Nested
    @DisplayName("SELECT ORDER BY 정렬 테스트")
    inner class SelectOrderByTest {

        @BeforeEach
        fun setUp() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR", "age" to "INT"))
            tableService.insert("users", mapOf("id" to "1", "name" to "Charlie", "age" to "35"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Alice", "age" to "25"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Bob", "age" to "30"))
        }

        @Test
        @DisplayName("name ASC 정렬")
        fun `name ASC 정렬`() {
            val result = tableService.select("users", orderBy = listOf(OrderByColumn("name", true)))
            assertThat(result!!.rows.map { it["name"] }).containsExactly("Alice", "Bob", "Charlie")
        }

        @Test
        @DisplayName("name DESC 정렬")
        fun `name DESC 정렬`() {
            val result = tableService.select("users", orderBy = listOf(OrderByColumn("name", false)))
            assertThat(result!!.rows.map { it["name"] }).containsExactly("Charlie", "Bob", "Alice")
        }

        @Test
        @DisplayName("INT 타입 숫자 순서 정렬 (사전순 아님)")
        fun `INT 타입 숫자 순서 정렬 (사전순 아님)`() {
            tableService.createTable("nums", mapOf("val" to "INT"))
            tableService.insert("nums", mapOf("val" to "3"))
            tableService.insert("nums", mapOf("val" to "100"))
            tableService.insert("nums", mapOf("val" to "25"))
            val result = tableService.select("nums", orderBy = listOf(OrderByColumn("val", true)))
            assertThat(result!!.rows.map { it["val"] }).containsExactly("3", "25", "100")
        }

        @Test
        @DisplayName("다중 컬럼 정렬 (name ASC, age DESC)")
        fun `다중 컬럼 정렬 (name ASC, age DESC)`() {
            tableService.insert("users", mapOf("id" to "4", "name" to "Alice", "age" to "20"))
            val result = tableService.select("users",
                orderBy = listOf(OrderByColumn("name", true), OrderByColumn("age", false))
            )
            // Alice 중 age DESC: 25 먼저, 20 나중
            assertThat(result!!.rows[0]["name"]).isEqualTo("Alice")
            assertThat(result.rows[0]["age"]).isEqualTo("25")
            assertThat(result.rows[1]["age"]).isEqualTo("20")
        }

        @Test
        @DisplayName("ORDER BY 없음 - 삽입 순서 유지")
        fun `ORDER BY 없음 - 삽입 순서 유지`() {
            val result = tableService.select("users")
            assertThat(result!!.rows.map { it["id"] }).containsExactly("1", "2", "3")
        }

        @Test
        @DisplayName("WHERE + ORDER BY 조합")
        fun `WHERE + ORDER BY 조합`() {
            val result = tableService.select("users",
                whereString = "age > 25",
                orderBy = listOf(OrderByColumn("name", true))
            )
            // age>25: Charlie(35), Bob(30) → name ASC: Bob, Charlie
            assertThat(result!!.rows.map { it["name"] }).containsExactly("Bob", "Charlie")
        }

        @Test
        @DisplayName("존재하지 않는 컬럼 ORDER BY - 예외 발생")
        fun `존재하지 않는 컬럼 ORDER BY - 예외 발생`() {
            assertThrows<ColumnNotFoundException> {
                tableService.select("users", orderBy = listOf(OrderByColumn("email", true)))
            }
        }

        @Test
        @DisplayName("VARCHAR 사전순 정렬 확인")
        fun `VARCHAR 사전순 정렬 확인`() {
            val result = tableService.select("users", orderBy = listOf(OrderByColumn("name", true)))
            assertThat(result!!.rows.map { it["name"] }).isSortedAccordingTo(compareBy { it })
        }
    }

    @Nested
    @DisplayName("SELECT LIMIT/OFFSET 페이지네이션 테스트")
    inner class SelectLimitOffsetTest {

        @BeforeEach
        fun setUp() {
            tableService.createTable("items", mapOf("id" to "INT"))
            (1..10).forEach { i -> tableService.insert("items", mapOf("id" to "$i")) }
        }

        @Test
        @DisplayName("LIMIT만 지정")
        fun `LIMIT만 지정`() {
            val result = tableService.select("items", limit = 3)
            assertThat(result!!.rows).hasSize(3)
            assertThat(result.rows[0]["id"]).isEqualTo("1")
        }

        @Test
        @DisplayName("OFFSET만 지정")
        fun `OFFSET만 지정`() {
            val result = tableService.select("items", offset = 7)
            assertThat(result!!.rows).hasSize(3)  // id=8,9,10
        }

        @Test
        @DisplayName("LIMIT + OFFSET 조합")
        fun `LIMIT + OFFSET 조합`() {
            val result = tableService.select("items", limit = 3, offset = 5)
            assertThat(result!!.rows).hasSize(3)
            assertThat(result.rows[0]["id"]).isEqualTo("6")
        }

        @Test
        @DisplayName("LIMIT이 전체보다 큰 경우 - 전체 반환")
        fun `LIMIT이 전체보다 큰 경우 - 전체 반환`() {
            val result = tableService.select("items", limit = 100)
            assertThat(result!!.rows).hasSize(10)
        }

        @Test
        @DisplayName("OFFSET이 전체보다 큰 경우 - 빈 rows 반환")
        fun `OFFSET이 전체보다 큰 경우 - 빈 rows 반환`() {
            val result = tableService.select("items", offset = 100)
            assertThat(result).isNotNull()
            assertThat(result!!.rows).isEmpty()
        }

        @Test
        @DisplayName("LIMIT = 0")
        fun `LIMIT = 0`() {
            val result = tableService.select("items", limit = 0)
            assertThat(result!!.rows).isEmpty()
        }

        @Test
        @DisplayName("ORDER BY + LIMIT 조합")
        fun `ORDER BY + LIMIT 조합`() {
            val result = tableService.select("items",
                orderBy = listOf(OrderByColumn("id", false)),
                limit = 3
            )
            assertThat(result!!.rows.map { it["id"] }).containsExactly("10", "9", "8")
        }

        @Test
        @DisplayName("풀 파이프라인 (WHERE + ORDER BY + LIMIT + OFFSET)")
        fun `풀 파이프라인 (WHERE + ORDER BY + LIMIT + OFFSET)`() {
            // WHERE id>3(7개) → DESC 정렬(10,9,8,7,6,5,4) → OFFSET 1(9,8,7,6,5,4) → LIMIT 3(9,8,7)
            val result = tableService.select("items",
                whereString = "id > 3",
                orderBy = listOf(OrderByColumn("id", false)),
                limit = 3,
                offset = 1
            )
            assertThat(result!!.rows.map { it["id"] }).containsExactly("9", "8", "7")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Phase 2 DML 확장 테스트
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("UPDATE 테스트")
    inner class UpdateTest {

        @BeforeEach
        fun setupTable() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
            tableService.insert("users", mapOf("id" to "1", "name" to "Alice"))
            tableService.insert("users", mapOf("id" to "2", "name" to "Bob"))
            tableService.insert("users", mapOf("id" to "3", "name" to "Charlie"))
        }

        @Test
        @DisplayName("U-01: WHERE 조건에 맞는 단일 행 업데이트")
        fun updateSingleRowMatchingWhereConditionTest() {
            val updatedCount = tableService.update("users", mapOf("name" to "Bobby"), "id=2")

            assertEquals(1, updatedCount)
            val rows = tableService.select("users")!!.rows
            assertTrue(rows.any { it["id"] == "2" && it["name"] == "Bobby" })
            assertTrue(rows.any { it["id"] == "1" && it["name"] == "Alice" })
            assertTrue(rows.any { it["id"] == "3" && it["name"] == "Charlie" })
        }

        @Test
        @DisplayName("U-02: WHERE 없이 전체 행 업데이트")
        fun updateAllRowsWhenNoWhereClauseGivenTest() {
            val updatedCount = tableService.update("users", mapOf("name" to "X"), null)

            assertEquals(3, updatedCount)
            val rows = tableService.select("users")!!.rows
            assertTrue(rows.all { it["name"] == "X" })
        }

        @Test
        @DisplayName("U-03: WHERE 매칭 없을 때 0 반환, 예외 없이 성공")
        fun updateReturnsZeroWhenNoRowsMatchWhereConditionTest() {
            val updatedCount = tableService.update("users", mapOf("name" to "Ghost"), "id=99")

            assertEquals(0, updatedCount)
            val rows = tableService.select("users")!!.rows
            assertTrue(rows.any { it["id"] == "1" && it["name"] == "Alice" })
            assertTrue(rows.any { it["id"] == "2" && it["name"] == "Bob" })
        }

        @Test
        @DisplayName("U-04: 존재하지 않는 컬럼 SET 시 ColumnNotFoundException")
        fun updateThrowsColumnNotFoundExceptionWhenSetColumnDoesNotExistTest() {
            assertThrows<ColumnNotFoundException> {
                tableService.update("users", mapOf("unknown" to "value"), null)
            }
        }

        @Test
        @DisplayName("U-05: INT 컬럼에 비정수 값 SET 시 TypeMismatchException")
        fun updateThrowsTypeMismatchExceptionWhenIntColumnReceivesNonIntegerValueTest() {
            assertThrows<TypeMismatchException> {
                tableService.update("users", mapOf("id" to "not-a-number"), null)
            }
        }
    }

    @Nested
    @DisplayName("INSERT 다중 행 테스트")
    inner class InsertBatchTest {

        @BeforeEach
        fun setupTable() {
            tableService.createTable("users", mapOf("id" to "INT", "name" to "VARCHAR"))
        }

        @Test
        @DisplayName("B-01: 다중 행 삽입 기본 동작")
        fun insertBatchInsertsAllRowsSuccessfullyTest() {
            val rows = listOf(
                mapOf("id" to "1", "name" to "Alice"),
                mapOf("id" to "2", "name" to "Bob"),
                mapOf("id" to "3", "name" to "Charlie"),
            )

            assertDoesNotThrow { tableService.insertBatch("users", rows) }

            val result = tableService.select("users")!!.rows
            assertEquals(3, result.size)
            assertTrue(result.any { it["id"] == "1" && it["name"] == "Alice" })
            assertTrue(result.any { it["id"] == "2" && it["name"] == "Bob" })
            assertTrue(result.any { it["id"] == "3" && it["name"] == "Charlie" })
        }

        @Test
        @DisplayName("B-02: 원자성 — 마지막 행 타입 오류 시 전체 실패")
        fun insertBatchRollsBackAllRowsWhenLastRowHasTypeMismatchTest() {
            val rows = listOf(
                mapOf("id" to "1", "name" to "Alice"),
                mapOf("id" to "bad", "name" to "Bob"),
            )

            assertThrows<TypeMismatchException> {
                tableService.insertBatch("users", rows)
            }
            val result = tableService.select("users")!!.rows
            assertEquals(0, result.size)
        }

        @Test
        @DisplayName("B-03: 원자성 — 첫 번째 행 타입 오류 시 전체 실패")
        fun insertBatchRollsBackAllRowsWhenFirstRowHasTypeMismatchTest() {
            val rows = listOf(
                mapOf("id" to "bad", "name" to "Alice"),
                mapOf("id" to "2", "name" to "Bob"),
            )

            assertThrows<TypeMismatchException> {
                tableService.insertBatch("users", rows)
            }
            val result = tableService.select("users")!!.rows
            assertEquals(0, result.size)
        }
    }
}
