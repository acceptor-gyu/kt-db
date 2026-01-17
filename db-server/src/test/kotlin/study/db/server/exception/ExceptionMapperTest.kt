package study.db.server.exception

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import study.db.common.protocol.DbResponse

@DisplayName("ExceptionMapper 테스트")
class ExceptionMapperTest {

    @Test
    @DisplayName("ColumnNotFoundException을 400 에러로 변환")
    fun `maps ColumnNotFoundException to 400 error`() {
        val exception = ColumnNotFoundException("users", "email")
        val response = ExceptionMapper.mapToResponse(exception)

        assertFalse(response.success)
        assertEquals(400, response.errorCode)
        assertTrue(response.message!!.contains("email"))
        assertTrue(response.message!!.contains("users"))
    }

    @Test
    @DisplayName("TypeMismatchException을 400 에러로 변환")
    fun `maps TypeMismatchException to 400 error`() {
        val exception = TypeMismatchException("abc", "INT", "Cannot parse as INT")
        val response = ExceptionMapper.mapToResponse(exception)

        assertFalse(response.success)
        assertEquals(400, response.errorCode)
        assertTrue(response.message!!.contains("abc"))
        assertTrue(response.message!!.contains("INT"))
    }

    @Test
    @DisplayName("UnsupportedTypeException을 400 에러로 변환")
    fun `maps UnsupportedTypeException to 400 error`() {
        val exception = UnsupportedTypeException("BLOB is not supported")
        val response = ExceptionMapper.mapToResponse(exception)

        assertFalse(response.success)
        assertEquals(400, response.errorCode)
        assertTrue(response.message!!.contains("BLOB"))
    }

    @Test
    @DisplayName("IllegalArgumentException을 400 에러로 변환")
    fun `maps IllegalArgumentException to 400 error`() {
        val exception = IllegalArgumentException("Invalid SQL syntax")
        val response = ExceptionMapper.mapToResponse(exception)

        assertFalse(response.success)
        assertEquals(400, response.errorCode)
        assertTrue(response.message!!.contains("Invalid SQL syntax"))
    }

    @Test
    @DisplayName("IllegalStateException을 404 에러로 변환")
    fun `maps IllegalStateException to 404 error`() {
        val exception = IllegalStateException("Table not found")
        val response = ExceptionMapper.mapToResponse(exception)

        assertFalse(response.success)
        assertEquals(404, response.errorCode)
        assertTrue(response.message!!.contains("Table not found"))
    }

    @Test
    @DisplayName("ResourceAlreadyExistsException을 409 에러로 변환")
    fun `maps ResourceAlreadyExistsException to 409 error`() {
        val exception = ResourceAlreadyExistsException("Table", "users")
        val response = ExceptionMapper.mapToResponse(exception)

        assertFalse(response.success)
        assertEquals(409, response.errorCode)
        assertTrue(response.message!!.contains("Table"))
        assertTrue(response.message!!.contains("users"))
        assertTrue(response.message!!.contains("already exists"))
    }

    @Test
    @DisplayName("일반 Exception을 500 에러로 변환")
    fun `maps generic Exception to 500 error`() {
        val exception = RuntimeException("Unexpected error")
        val response = ExceptionMapper.mapToResponse(exception, 123L)

        assertFalse(response.success)
        assertEquals(500, response.errorCode)
        assertTrue(response.message!!.contains("Internal error"))
        assertTrue(response.message!!.contains("Unexpected error"))
    }

    @Test
    @DisplayName("executeWithExceptionHandling - 성공 케이스")
    fun `executeWithExceptionHandling returns success response`() {
        val response = ExceptionMapper.executeWithExceptionHandling {
            DbResponse(success = true, message = "Success")
        }

        assertTrue(response.success)
        assertEquals("Success", response.message)
    }

    @Test
    @DisplayName("executeWithExceptionHandling - 예외 발생 시 자동 변환")
    fun `executeWithExceptionHandling catches and converts exception`() {
        val response = ExceptionMapper.executeWithExceptionHandling {
            throw TypeMismatchException("abc", "INT", "Cannot parse")
        }

        assertFalse(response.success)
        assertEquals(400, response.errorCode)
        assertTrue(response.message!!.contains("Type mismatch"))
    }

    @Test
    @DisplayName("executeWithExceptionHandling - connectionId 로깅")
    fun `executeWithExceptionHandling logs with connectionId`() {
        val response = ExceptionMapper.executeWithExceptionHandling(456L) {
            throw RuntimeException("Test error")
        }

        assertFalse(response.success)
        assertEquals(500, response.errorCode)
    }

    @Test
    @DisplayName("다양한 예외를 올바른 에러 코드로 변환")
    fun `maps various exceptions to correct error codes`() {
        val testCases = mapOf(
            ColumnNotFoundException("table", "col") to 400,
            TypeMismatchException("val", "type", "reason") to 400,
            UnsupportedTypeException("msg") to 400,
            IllegalArgumentException("msg") to 400,
            ResourceAlreadyExistsException("Table", "name") to 409,
            IllegalStateException("msg") to 404,
            RuntimeException("msg") to 500,
            Exception("msg") to 500
        )

        testCases.forEach { (exception, expectedCode) ->
            val response = ExceptionMapper.mapToResponse(exception)
            assertEquals(expectedCode, response.errorCode,
                "Exception ${exception::class.simpleName} should map to $expectedCode")
        }
    }
}
