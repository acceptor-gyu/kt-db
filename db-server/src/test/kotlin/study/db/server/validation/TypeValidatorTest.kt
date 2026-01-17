package study.db.server.validation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import study.db.server.exception.TypeMismatchException
import study.db.server.exception.UnsupportedTypeException

@DisplayName("TypeValidator 단위 테스트")
class TypeValidatorTest {

    @Test
    @DisplayName("INT 검증 - 성공")
    fun `validates INT successfully`() {
        assertDoesNotThrow { TypeValidator.validate("123", "INT") }
        assertDoesNotThrow { TypeValidator.validate("-456", "INT") }
        assertDoesNotThrow { TypeValidator.validate("0", "INT") }
    }

    @Test
    @DisplayName("INT 검증 - 실패 (문자열)")
    fun `fails INT validation with string`() {
        assertThrows<TypeMismatchException> { TypeValidator.validate("abc", "INT") }
    }

    @Test
    @DisplayName("INT 검증 - 실패 (소수점)")
    fun `fails INT validation with decimal`() {
        assertThrows<TypeMismatchException> { TypeValidator.validate("12.5", "INT") }
    }

    @Test
    @DisplayName("INT 검증 - 실패 (빈 문자열)")
    fun `fails INT validation with empty string`() {
        assertThrows<TypeMismatchException> { TypeValidator.validate("", "INT") }
    }

    @Test
    @DisplayName("INT 검증 - 성공 (최대값)")
    fun `validates INT max value`() {
        assertDoesNotThrow { TypeValidator.validate("2147483647", "INT") }
    }

    @Test
    @DisplayName("INT 검증 - 성공 (최소값)")
    fun `validates INT min value`() {
        assertDoesNotThrow { TypeValidator.validate("-2147483648", "INT") }
    }

    @Test
    @DisplayName("INT 검증 - 실패 (범위 초과)")
    fun `fails INT validation when exceeds range`() {
        assertThrows<TypeMismatchException> { TypeValidator.validate("2147483648", "INT") }
        assertThrows<TypeMismatchException> { TypeValidator.validate("-2147483649", "INT") }
    }

    @Test
    @DisplayName("VARCHAR 검증 - 항상 성공")
    fun `validates VARCHAR always succeeds`() {
        assertDoesNotThrow { TypeValidator.validate("any string", "VARCHAR") }
        assertDoesNotThrow { TypeValidator.validate("", "VARCHAR") }
        assertDoesNotThrow { TypeValidator.validate("123", "VARCHAR") }
        assertDoesNotThrow { TypeValidator.validate("special !@#$%", "VARCHAR") }
    }

    @Test
    @DisplayName("BOOLEAN 검증 - 성공 (true)")
    fun `validates BOOLEAN true successfully`() {
        assertDoesNotThrow { TypeValidator.validate("true", "BOOLEAN") }
        assertDoesNotThrow { TypeValidator.validate("TRUE", "BOOLEAN") }
        assertDoesNotThrow { TypeValidator.validate("True", "BOOLEAN") }
    }

    @Test
    @DisplayName("BOOLEAN 검증 - 성공 (false)")
    fun `validates BOOLEAN false successfully`() {
        assertDoesNotThrow { TypeValidator.validate("false", "BOOLEAN") }
        assertDoesNotThrow { TypeValidator.validate("FALSE", "BOOLEAN") }
        assertDoesNotThrow { TypeValidator.validate("False", "BOOLEAN") }
    }

    @Test
    @DisplayName("BOOLEAN 검증 - 실패")
    fun `fails BOOLEAN validation with invalid values`() {
        assertThrows<TypeMismatchException> { TypeValidator.validate("yes", "BOOLEAN") }
        assertThrows<TypeMismatchException> { TypeValidator.validate("no", "BOOLEAN") }
        assertThrows<TypeMismatchException> { TypeValidator.validate("1", "BOOLEAN") }
        assertThrows<TypeMismatchException> { TypeValidator.validate("0", "BOOLEAN") }
    }

    @Test
    @DisplayName("TIMESTAMP 검증 - 성공 (ISO-8601)")
    fun `validates TIMESTAMP successfully with ISO-8601`() {
        assertDoesNotThrow { TypeValidator.validate("2024-01-15T10:30:00Z", "TIMESTAMP") }
        assertDoesNotThrow { TypeValidator.validate("2024-12-31T23:59:59Z", "TIMESTAMP") }
    }

    @Test
    @DisplayName("TIMESTAMP 검증 - 성공 (LocalDateTime)")
    fun `validates TIMESTAMP successfully with LocalDateTime format`() {
        assertDoesNotThrow { TypeValidator.validate("2024-01-15 10:30:00", "TIMESTAMP") }
        assertDoesNotThrow { TypeValidator.validate("2024-12-31 23:59:59", "TIMESTAMP") }
    }

    @Test
    @DisplayName("TIMESTAMP 검증 - 실패")
    fun `fails TIMESTAMP validation with invalid format`() {
        assertThrows<TypeMismatchException> { TypeValidator.validate("2024/01/15", "TIMESTAMP") }
        assertThrows<TypeMismatchException> { TypeValidator.validate("invalid", "TIMESTAMP") }
        assertThrows<TypeMismatchException> { TypeValidator.validate("", "TIMESTAMP") }
    }

    @Test
    @DisplayName("지원하지 않는 타입 - BLOB")
    fun `throws exception for unsupported BLOB type`() {
        assertThrows<UnsupportedTypeException> { TypeValidator.validate("data", "BLOB") }
    }

    @Test
    @DisplayName("지원하지 않는 타입 - BIGINT")
    fun `throws exception for unsupported BIGINT type`() {
        assertThrows<UnsupportedTypeException> { TypeValidator.validate("123", "BIGINT") }
    }

    @Test
    @DisplayName("지원하지 않는 타입 - TEXT")
    fun `throws exception for unsupported TEXT type`() {
        assertThrows<UnsupportedTypeException> { TypeValidator.validate("text", "TEXT") }
    }

    @Test
    @DisplayName("대소문자 무관 타입 검증 - int")
    fun `validates types case insensitively for int`() {
        assertDoesNotThrow { TypeValidator.validate("123", "int") }
        assertDoesNotThrow { TypeValidator.validate("123", "Int") }
    }

    @Test
    @DisplayName("대소문자 무관 타입 검증 - varchar")
    fun `validates types case insensitively for varchar`() {
        assertDoesNotThrow { TypeValidator.validate("text", "varchar") }
        assertDoesNotThrow { TypeValidator.validate("text", "VarChar") }
    }

    @Test
    @DisplayName("대소문자 무관 타입 검증 - boolean")
    fun `validates types case insensitively for boolean`() {
        assertDoesNotThrow { TypeValidator.validate("true", "boolean") }
        assertDoesNotThrow { TypeValidator.validate("false", "Boolean") }
    }

    @Test
    @DisplayName("대소문자 무관 타입 검증 - timestamp")
    fun `validates types case insensitively for timestamp`() {
        assertDoesNotThrow { TypeValidator.validate("2024-01-15T10:30:00Z", "timestamp") }
        assertDoesNotThrow { TypeValidator.validate("2024-01-15 10:30:00", "TimeStamp") }
    }
}
