package study.db.api.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import study.db.common.protocol.DbCommand

@DisplayName("SqlParser 테스트")
class SqlParserTest {

    @Test
    @DisplayName("CREATE TABLE 파싱")
    fun `parses CREATE TABLE query`() {
        val sql = "CREATE TABLE users (id INT, name VARCHAR, age INT)"
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.CREATE_TABLE, request.command)
        assertEquals("users", request.tableName)
        assertNotNull(request.columns)
        assertEquals(3, request.columns?.size)
        assertEquals("INT", request.columns?.get("id"))
        assertEquals("VARCHAR", request.columns?.get("name"))
        assertEquals("INT", request.columns?.get("age"))
    }

    @Test
    @DisplayName("CREATE TABLE 파싱 (세미콜론 포함)")
    fun `parses CREATE TABLE query with semicolon`() {
        val sql = "CREATE TABLE products (id INT, name VARCHAR);"
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.CREATE_TABLE, request.command)
        assertEquals("products", request.tableName)
        assertEquals(2, request.columns?.size)
    }

    @Test
    @DisplayName("INSERT INTO 파싱 (double quotes)")
    fun `parses INSERT INTO query with double quotes`() {
        val sql = """INSERT INTO users VALUES (id="1", name="John", age="30")"""
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.INSERT, request.command)
        assertEquals("users", request.tableName)
        assertNotNull(request.values)
        assertEquals("1", request.values?.get("id"))
        assertEquals("John", request.values?.get("name"))
        assertEquals("30", request.values?.get("age"))
    }

    @Test
    @DisplayName("INSERT INTO 파싱 (single quotes)")
    fun `parses INSERT INTO query with single quotes`() {
        val sql = """INSERT INTO users VALUES (id='1', name='Jane')"""
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.INSERT, request.command)
        assertEquals("users", request.tableName)
        assertEquals("1", request.values?.get("id"))
        assertEquals("Jane", request.values?.get("name"))
    }

    @Test
    @DisplayName("INSERT INTO 파싱 (quotes 없음)")
    fun `parses INSERT INTO query without quotes`() {
        val sql = """INSERT INTO users VALUES (id=1, active=true)"""
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.INSERT, request.command)
        assertEquals("users", request.tableName)
        assertEquals("1", request.values?.get("id"))
        assertEquals("true", request.values?.get("active"))
    }

    @Test
    @DisplayName("SELECT 파싱")
    fun `parses SELECT query`() {
        val sql = "SELECT * FROM users"
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.SELECT, request.command)
        assertEquals("users", request.tableName)
        assertNull(request.condition)
    }

    @Test
    @DisplayName("SELECT 파싱 (WHERE 절 포함)")
    fun `parses SELECT query with WHERE clause`() {
        val sql = """SELECT id, name FROM users WHERE id="1" """
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.SELECT, request.command)
        assertEquals("users", request.tableName)
        assertEquals("""id="1"""", request.condition?.trim())
    }

    @Test
    @DisplayName("DROP TABLE 파싱")
    fun `parses DROP TABLE query`() {
        val sql = "DROP TABLE users"
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.DROP_TABLE, request.command)
        assertEquals("users", request.tableName)
    }

    @Test
    @DisplayName("DROP TABLE 파싱 (세미콜론 포함)")
    fun `parses DROP TABLE query with semicolon`() {
        val sql = "DROP TABLE products;"
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.DROP_TABLE, request.command)
        assertEquals("products", request.tableName)
    }

    @Test
    @DisplayName("EXPLAIN 파싱")
    fun `parses EXPLAIN query`() {
        val sql = "EXPLAIN SELECT * FROM users"
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.EXPLAIN, request.command)
        assertEquals("SELECT * FROM users", request.sql)
    }

    @Test
    @DisplayName("EXPLAIN 파싱 (복잡한 쿼리)")
    fun `parses EXPLAIN query with complex SELECT`() {
        val sql = """EXPLAIN SELECT id, name FROM users WHERE age > 20"""
        val request = SqlParser.parse(sql)

        assertEquals(DbCommand.EXPLAIN, request.command)
        assertEquals("SELECT id, name FROM users WHERE age > 20", request.sql)
    }

    @Test
    @DisplayName("대소문자 구분 없음")
    fun `is case insensitive`() {
        val sql1 = "create table users (id int)"
        val sql2 = "CREATE TABLE users (id INT)"
        val sql3 = "CrEaTe TaBlE users (id InT)"

        val request1 = SqlParser.parse(sql1)
        val request2 = SqlParser.parse(sql2)
        val request3 = SqlParser.parse(sql3)

        assertEquals(DbCommand.CREATE_TABLE, request1.command)
        assertEquals(DbCommand.CREATE_TABLE, request2.command)
        assertEquals(DbCommand.CREATE_TABLE, request3.command)
    }

    @Test
    @DisplayName("지원하지 않는 쿼리는 예외 발생")
    fun `throws exception for unsupported query`() {
        val sql = "UPDATE users SET name='Jane' WHERE id=1"

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SqlParser.parse(sql)
        }

        assertTrue(exception.message!!.contains("Unsupported SQL query"))
    }

    @Test
    @DisplayName("잘못된 CREATE TABLE 구문은 예외 발생")
    fun `throws exception for invalid CREATE TABLE syntax`() {
        val sql = "CREATE TABLE users"  // Missing column definitions

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SqlParser.parse(sql)
        }

        assertTrue(exception.message!!.contains("Invalid CREATE TABLE syntax"))
    }

    @Test
    @DisplayName("잘못된 INSERT 구문은 예외 발생")
    fun `throws exception for invalid INSERT syntax`() {
        val sql = "INSERT INTO users"  // Missing VALUES clause

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SqlParser.parse(sql)
        }

        assertTrue(exception.message!!.contains("Invalid INSERT syntax"))
    }
}
