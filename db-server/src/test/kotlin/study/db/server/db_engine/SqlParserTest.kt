package study.db.server.db_engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import study.db.server.db_engine.dto.WhereCondition

/**
 * SqlParser 테스트
 *
 * SqlParser는 SQL 쿼리 문자열을 파싱하여 구조화된 데이터로 변환합니다.
 * JSqlParser 라이브러리를 사용하여 SELECT 문을 분석하고,
 * 테이블명, 컬럼, WHERE 조건, ORDER BY 절 등을 추출합니다.
 */
@DisplayName("SqlParser 테스트")
class SqlParserTest {

    private lateinit var sqlParser: SqlParser

    @BeforeEach
    fun setup() {
        sqlParser = SqlParser()
    }

    @Nested
    @DisplayName("기본 SELECT 쿼리 파싱")
    inner class BasicSelectTest {

        @Test
        @DisplayName("SELECT * FROM 기본 쿼리 파싱")
        fun `parses simple select all query`() {
            // Given: SELECT * 쿼리
            val sql = "SELECT * FROM users"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 테이블명과 컬럼 정보 추출
            assertEquals("users", result.tableName)
            assertEquals(listOf("*"), result.selectColumns)
            assertTrue(result.whereConditions.isEmpty())
            assertTrue(result.orderBy.isEmpty())
        }

        @Test
        @DisplayName("특정 컬럼 선택 쿼리 파싱")
        fun `parses select with specific columns`() {
            // Given: 특정 컬럼을 선택하는 쿼리
            val sql = "SELECT id, name, email FROM users"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 선택한 컬럼들이 추출됨
            assertEquals("users", result.tableName)
            assertEquals(listOf("id", "name", "email"), result.selectColumns)
            assertTrue(result.whereConditions.isEmpty())
        }

        @Test
        @DisplayName("컬럼 별칭(AS)이 있는 쿼리 파싱")
        fun `parses select with column aliases`() {
            // Given: 컬럼 별칭을 사용하는 쿼리
            val sql = "SELECT user_id AS id, user_name AS name FROM users"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 원본 컬럼명만 추출됨 (별칭 제거)
            assertEquals("users", result.tableName)
            assertEquals(listOf("user_id", "user_name"), result.selectColumns)
        }
    }

    @Nested
    @DisplayName("WHERE 절 파싱 - 단일 조건")
    inner class WhereClauseSingleConditionTest {

        @Test
        @DisplayName("등호(=) 조건 파싱")
        fun `parses where clause with equals operator`() {
            // Given: 등호 조건 쿼리
            val sql = "SELECT * FROM users WHERE status = 'active'"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: WHERE 조건 추출
            assertEquals(1, result.whereConditions.size)
            val condition = result.whereConditions[0]
            assertEquals("status", condition.column)
            assertEquals("=", condition.operator)
            assertEquals("'active'", condition.value)
        }

        @Test
        @DisplayName("부등호(!=) 조건 파싱")
        fun `parses where clause with not equals operator`() {
            // Given: 부등호 조건 쿼리
            val sql = "SELECT * FROM users WHERE status != 'inactive'"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 부등호 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("status", result.whereConditions[0].column)
            assertEquals("!=", result.whereConditions[0].operator)
            assertEquals("'inactive'", result.whereConditions[0].value)
        }

        @Test
        @DisplayName("크다(>) 조건 파싱")
        fun `parses where clause with greater than operator`() {
            // Given: 크다 조건 쿼리
            val sql = "SELECT * FROM users WHERE age > 20"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 크다 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("age", result.whereConditions[0].column)
            assertEquals(">", result.whereConditions[0].operator)
            assertEquals("20", result.whereConditions[0].value)
        }

        @Test
        @DisplayName("크거나 같다(>=) 조건 파싱")
        fun `parses where clause with greater than or equals operator`() {
            // Given: 크거나 같다 조건 쿼리
            val sql = "SELECT * FROM users WHERE age >= 18"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 크거나 같다 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("age", result.whereConditions[0].column)
            assertEquals(">=", result.whereConditions[0].operator)
            assertEquals("18", result.whereConditions[0].value)
        }

        @Test
        @DisplayName("작다(<) 조건 파싱")
        fun `parses where clause with less than operator`() {
            // Given: 작다 조건 쿼리
            val sql = "SELECT * FROM users WHERE age < 30"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 작다 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("age", result.whereConditions[0].column)
            assertEquals("<", result.whereConditions[0].operator)
            assertEquals("30", result.whereConditions[0].value)
        }

        @Test
        @DisplayName("작거나 같다(<=) 조건 파싱")
        fun `parses where clause with less than or equals operator`() {
            // Given: 작거나 같다 조건 쿼리
            val sql = "SELECT * FROM users WHERE age <= 65"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 작거나 같다 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("age", result.whereConditions[0].column)
            assertEquals("<=", result.whereConditions[0].operator)
            assertEquals("65", result.whereConditions[0].value)
        }
    }

    @Nested
    @DisplayName("WHERE 절 파싱 - 복합 조건")
    inner class WhereClauseMultipleConditionsTest {

        @Test
        @DisplayName("AND 조건 파싱")
        fun `parses where clause with AND operator`() {
            // Given: AND로 연결된 조건 쿼리
            val sql = "SELECT * FROM users WHERE age > 20 AND status = 'active'"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 두 조건 모두 추출
            assertEquals(2, result.whereConditions.size)

            val condition1 = result.whereConditions[0]
            assertEquals("age", condition1.column)
            assertEquals(">", condition1.operator)
            assertEquals("20", condition1.value)

            val condition2 = result.whereConditions[1]
            assertEquals("status", condition2.column)
            assertEquals("=", condition2.operator)
            assertEquals("'active'", condition2.value)
        }

        @Test
        @DisplayName("OR 조건 파싱")
        fun `parses where clause with OR operator`() {
            // Given: OR로 연결된 조건 쿼리
            val sql = "SELECT * FROM users WHERE status = 'active' OR status = 'pending'"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 두 조건 모두 추출
            assertEquals(2, result.whereConditions.size)

            assertEquals("status", result.whereConditions[0].column)
            assertEquals("=", result.whereConditions[0].operator)
            assertEquals("'active'", result.whereConditions[0].value)

            assertEquals("status", result.whereConditions[1].column)
            assertEquals("=", result.whereConditions[1].operator)
            assertEquals("'pending'", result.whereConditions[1].value)
        }

        @Test
        @DisplayName("다중 AND 조건 파싱")
        fun `parses where clause with multiple AND operators`() {
            // Given: 여러 AND 조건이 있는 쿼리
            val sql = "SELECT * FROM users WHERE age > 18 AND age < 65 AND status = 'active'"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 세 조건 모두 추출
            assertEquals(3, result.whereConditions.size)
            assertEquals("age", result.whereConditions[0].column)
            assertEquals(">", result.whereConditions[0].operator)

            assertEquals("age", result.whereConditions[1].column)
            assertEquals("<", result.whereConditions[1].operator)

            assertEquals("status", result.whereConditions[2].column)
            assertEquals("=", result.whereConditions[2].operator)
        }
    }

    @Nested
    @DisplayName("WHERE 절 파싱 - 특수 연산자")
    inner class WhereClauseSpecialOperatorsTest {

        @Test
        @DisplayName("BETWEEN 연산자 파싱")
        fun `parses where clause with BETWEEN operator`() {
            // Given: BETWEEN 조건 쿼리
            val sql = "SELECT * FROM users WHERE age BETWEEN 20 AND 30"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: BETWEEN 조건 추출
            assertEquals(1, result.whereConditions.size)
            val condition = result.whereConditions[0]
            assertEquals("age", condition.column)
            assertEquals("BETWEEN", condition.operator)
            assertEquals("20 AND 30", condition.value)
        }

        @Test
        @DisplayName("LIKE 연산자 파싱")
        fun `parses where clause with LIKE operator`() {
            // Given: LIKE 조건 쿼리
            val sql = "SELECT * FROM users WHERE name LIKE 'John%'"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: LIKE 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("name", result.whereConditions[0].column)
            assertEquals("LIKE", result.whereConditions[0].operator)
            assertEquals("'John%'", result.whereConditions[0].value)
        }

        @Test
        @DisplayName("IS NULL 연산자 파싱")
        fun `parses where clause with IS NULL operator`() {
            // Given: IS NULL 조건 쿼리
            val sql = "SELECT * FROM users WHERE email IS NULL"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: IS NULL 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("email", result.whereConditions[0].column)
            assertEquals("IS NULL", result.whereConditions[0].operator)
            assertEquals("", result.whereConditions[0].value)
        }

        @Test
        @DisplayName("IS NOT NULL 연산자 파싱")
        fun `parses where clause with IS NOT NULL operator`() {
            // Given: IS NOT NULL 조건 쿼리
            val sql = "SELECT * FROM users WHERE email IS NOT NULL"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: IS NOT NULL 조건 추출
            assertEquals(1, result.whereConditions.size)
            assertEquals("email", result.whereConditions[0].column)
            assertEquals("IS NOT NULL", result.whereConditions[0].operator)
            assertEquals("", result.whereConditions[0].value)
        }
    }

    @Nested
    @DisplayName("ORDER BY 절 파싱")
    inner class OrderByTest {

        @Test
        @DisplayName("단일 컬럼 ORDER BY 파싱")
        fun `parses order by with single column`() {
            // Given: ORDER BY 쿼리
            val sql = "SELECT * FROM users ORDER BY created_at"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: ORDER BY 컬럼 추출
            assertEquals(listOf("created_at"), result.orderBy)
        }

        @Test
        @DisplayName("DESC를 포함한 ORDER BY 파싱")
        fun `parses order by with DESC`() {
            // Given: DESC를 포함한 ORDER BY 쿼리
            val sql = "SELECT * FROM users ORDER BY created_at DESC"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: ORDER BY 컬럼 추출 (DESC는 컬럼명에 포함되지 않음)
            assertEquals(listOf("created_at"), result.orderBy)
        }

        @Test
        @DisplayName("ASC를 포함한 ORDER BY 파싱")
        fun `parses order by with ASC`() {
            // Given: ASC를 포함한 ORDER BY 쿼리
            val sql = "SELECT * FROM users ORDER BY name ASC"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: ORDER BY 컬럼 추출 (ASC는 컬럼명에 포함되지 않음)
            assertEquals(listOf("name"), result.orderBy)
        }

        @Test
        @DisplayName("다중 컬럼 ORDER BY 파싱")
        fun `parses order by with multiple columns`() {
            // Given: 여러 컬럼을 정렬하는 쿼리
            val sql = "SELECT * FROM users ORDER BY created_at DESC, name ASC"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 모든 ORDER BY 컬럼 추출
            assertEquals(listOf("created_at", "name"), result.orderBy)
        }
    }

    @Nested
    @DisplayName("복합 쿼리 파싱")
    inner class ComplexQueryTest {

        @Test
        @DisplayName("WHERE와 ORDER BY를 모두 포함한 쿼리 파싱")
        fun `parses query with both WHERE and ORDER BY`() {
            // Given: WHERE와 ORDER BY를 모두 포함한 쿼리
            val sql = "SELECT id, name FROM users WHERE age > 20 AND status = 'active' ORDER BY created_at DESC"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 모든 절이 올바르게 파싱됨
            assertEquals("users", result.tableName)
            assertEquals(listOf("id", "name"), result.selectColumns)
            assertEquals(2, result.whereConditions.size)
            assertEquals(listOf("created_at"), result.orderBy)
        }

        @Test
        @DisplayName("복잡한 WHERE 조건과 ORDER BY를 포함한 쿼리")
        fun `parses complex query with multiple conditions`() {
            // Given: 복잡한 조건의 쿼리
            val sql = """
                SELECT user_id AS id, user_name AS name, email
                FROM users
                WHERE age >= 18 AND age <= 65 AND status = 'active'
                ORDER BY created_at DESC, name ASC
            """.trimIndent()

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 모든 요소가 정확히 파싱됨
            assertEquals("users", result.tableName)
            assertEquals(listOf("user_id", "user_name", "email"), result.selectColumns)
            assertEquals(3, result.whereConditions.size)
            assertEquals(listOf("created_at", "name"), result.orderBy)
        }

        @Test
        @DisplayName("WHERE 없이 ORDER BY만 있는 쿼리")
        fun `parses query with only ORDER BY`() {
            // Given: ORDER BY만 있는 쿼리
            val sql = "SELECT * FROM users ORDER BY name"

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: WHERE는 비어있고 ORDER BY만 추출됨
            assertEquals("users", result.tableName)
            assertTrue(result.whereConditions.isEmpty())
            assertEquals(listOf("name"), result.orderBy)
        }
    }

    @Nested
    @DisplayName("예외 처리")
    inner class ExceptionTest {

        @Test
        @DisplayName("잘못된 SQL 문법 - 파싱 실패")
        fun `throws exception for invalid SQL syntax`() {
            // Given: 잘못된 SQL 문법
            val invalidSql = "SELEC * FORM users"

            // When & Then: 파싱 시 예외 발생
            assertThrows(Exception::class.java) {
                sqlParser.parseQuery(invalidSql)
            }
        }

        @Test
        @DisplayName("FROM 절이 없는 쿼리 - 파싱 실패")
        fun `throws exception for query without FROM clause`() {
            // Given: FROM 절이 없는 쿼리
            val invalidSql = "SELECT *"

            // When & Then: 파싱 시 예외 발생
            assertThrows(Exception::class.java) {
                sqlParser.parseQuery(invalidSql)
            }
        }

        @Test
        @DisplayName("빈 문자열 - 파싱 실패")
        fun `throws exception for empty string`() {
            // Given: 빈 문자열
            val emptySql = ""

            // When & Then: 파싱 시 예외 발생
            assertThrows(Exception::class.java) {
                sqlParser.parseQuery(emptySql)
            }
        }
    }

    @Nested
    @DisplayName("통합 시나리오")
    inner class IntegrationTest {

        @Test
        @DisplayName("실제 사용 시나리오 - 사용자 검색 쿼리")
        fun `parses realistic user search query`() {
            // Given: 실제 사용 가능한 사용자 검색 쿼리
            val sql = """
                SELECT id, name, email, created_at
                FROM users
                WHERE status = 'active' AND age >= 18 AND email IS NOT NULL
                ORDER BY created_at DESC
            """.trimIndent()

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 모든 정보가 정확히 추출됨
            assertEquals("users", result.tableName)
            assertEquals(listOf("id", "name", "email", "created_at"), result.selectColumns)
            assertEquals(3, result.whereConditions.size)

            // WHERE 조건 검증
            assertEquals("status", result.whereConditions[0].column)
            assertEquals("age", result.whereConditions[1].column)
            assertEquals("email", result.whereConditions[2].column)
            assertEquals("IS NOT NULL", result.whereConditions[2].operator)

            // ORDER BY 검증
            assertEquals(listOf("created_at"), result.orderBy)
        }

        @Test
        @DisplayName("실제 사용 시나리오 - 로그 조회 쿼리")
        fun `parses realistic log query`() {
            // Given: 로그 조회 쿼리
            val sql = """
                SELECT timestamp, level, message
                FROM logs
                WHERE level = 'ERROR' AND timestamp BETWEEN '2026-01-01' AND '2026-12-31'
                ORDER BY timestamp DESC
            """.trimIndent()

            // When: 쿼리 파싱
            val result = sqlParser.parseQuery(sql)

            // Then: 로그 쿼리가 정확히 파싱됨
            assertEquals("logs", result.tableName)
            assertEquals(listOf("timestamp", "level", "message"), result.selectColumns)
            assertEquals(2, result.whereConditions.size)

            val betweenCondition = result.whereConditions.find { it.operator == "BETWEEN" }
            assertNotNull(betweenCondition)
            assertEquals("timestamp", betweenCondition?.column)
            assertTrue(betweenCondition?.value?.contains("2026-01-01") == true)
            assertTrue(betweenCondition?.value?.contains("2026-12-31") == true)
        }
    }
}
