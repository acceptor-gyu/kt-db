package study.db.server.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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
            val result = tableService.insert("users", values)

            // Then: 삽입 성공
            assertTrue(result)

            // 데이터 확인
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals(values, table?.value)
        }

        @Test
        @DisplayName("존재하지 않는 테이블에 삽입 실패")
        fun `fails to insert into non-existent table`() {
            // Given: 존재하지 않는 테이블
            val values = mapOf("id" to "1")

            // When: 존재하지 않는 테이블에 삽입 시도
            val result = tableService.insert("non_existent", values)

            // Then: 삽입 실패
            assertFalse(result)
        }

        @Test
        @DisplayName("여러 번 삽입하면 값이 병합됨")
        fun `multiple inserts merge values`() {
            // Given: 초기 데이터
            val initialValues = mapOf("id" to "1", "name" to "John")
            tableService.insert("users", initialValues)

            // When: 추가 데이터 삽입 (같은 키를 가진 값)
            val additionalValues = mapOf("id" to "2", "name" to "Jane")
            tableService.insert("users", additionalValues)

            // Then: 맵 병합으로 같은 키는 덮어써짐
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals("2", table?.value?.get("id"))
            assertEquals("Jane", table?.value?.get("name"))
        }

        @Test
        @DisplayName("빈 값 삽입")
        fun `inserts empty values`() {
            // Given: 빈 값
            val emptyValues = emptyMap<String, String>()

            // When: 빈 값 삽입
            val result = tableService.insert("users", emptyValues)

            // Then: 삽입은 성공하지만 데이터는 없음
            assertTrue(result)
            val table = tableService.select("users")
            assertTrue(table?.value?.isEmpty() ?: false)
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
            assertEquals(values, table?.value)
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
            assertTrue(tableService.insert("users", userData))

            // Read
            val table = tableService.select("users")
            assertNotNull(table)
            assertEquals("users", table?.tableName)
            assertEquals(userData, table?.value)

            // Update (재삽입)
            val updatedData = mapOf("id" to "1", "name" to "John Doe")
            assertTrue(tableService.insert("users", updatedData))

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
            tableService.createTable("comments", mapOf("id" to "INT", "content" to "TEXT"))

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

            assertEquals("John", users?.value?.get("name"))
            assertEquals("First Post", posts?.value?.get("title"))
            assertEquals("Great!", comments?.value?.get("content"))
        }
    }
}
