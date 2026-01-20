package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VarcharFieldEncoder 테스트")
class VarcharFieldEncoderTest {

    private val encoder = VarcharFieldEncoder()

    @Test
    @DisplayName("일반 문자열 인코딩/디코딩")
    fun `encodes and decodes regular string`() {
        val value = "Hello, World!"
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        assertEquals(value, decoded)
        assertEquals(encoded.size, consumed)
    }

    @Test
    @DisplayName("빈 문자열 인코딩/디코딩")
    fun `encodes and decodes empty string`() {
        val value = ""
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        assertEquals(value, decoded)
        assertEquals(2, consumed)  // Length prefix only
    }

    @Test
    @DisplayName("UTF-8 다국어 문자열 인코딩/디코딩")
    fun `encodes and decodes UTF-8 strings`() {
        val testCases = listOf(
            "안녕하세요",
            "こんにちは",
            "你好",
            "مرحبا",
            "Привет"
        )

        testCases.forEach { value ->
            val encoded = encoder.encode(value)
            val (decoded, _) = encoder.decode(encoded, 0)
            assertEquals(value, decoded, "Failed for: $value")
        }
    }

    @Test
    @DisplayName("특수 문자 인코딩/디코딩")
    fun `encodes and decodes special characters`() {
        val value = "!@#\$%^&*()_+-=[]{}|;':\",./<>?"
        val encoded = encoder.encode(value)
        val (decoded, _) = encoder.decode(encoded, 0)

        assertEquals(value, decoded)
    }

    @Test
    @DisplayName("긴 문자열 인코딩/디코딩")
    fun `encodes and decodes long string`() {
        val value = "A".repeat(1000)
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        assertEquals(value, decoded)
        assertEquals(2 + 1000, consumed)
    }

    @Test
    @DisplayName("offset을 사용한 디코딩")
    fun `decodes with offset`() {
        val value = "Test"
        val encoded = encoder.encode(value)

        // 앞에 더미 데이터 추가
        val withPadding = ByteArray(10) + encoded
        val (decoded, consumed) = encoder.decode(withPadding, 10)

        assertEquals(value, decoded)
        assertEquals(encoded.size, consumed)
    }

    @Test
    @DisplayName("여러 문자열 연속 디코딩")
    fun `decodes multiple strings sequentially`() {
        val values = listOf("First", "Second", "Third")
        var currentBytes = ByteArray(0)

        // 모든 문자열 인코딩하여 연결
        values.forEach { value ->
            currentBytes += encoder.encode(value)
        }

        // 순차적으로 디코딩
        var offset = 0
        values.forEach { expectedValue ->
            val (decoded, consumed) = encoder.decode(currentBytes, offset)
            assertEquals(expectedValue, decoded)
            offset += consumed
        }

        assertEquals(currentBytes.size, offset)
    }

    @Test
    @DisplayName("인코딩 형식 검증 - length prefix")
    fun `verifies encoding format with length prefix`() {
        val value = "Hello"
        val encoded = encoder.encode(value)

        // Length는 2 bytes + "Hello"의 UTF-8 bytes (5)
        assertEquals(7, encoded.size)

        // First 2 bytes should be length (5) in Big Endian
        val length = ((encoded[0].toInt() and 0xFF) shl 8) or (encoded[1].toInt() and 0xFF)
        assertEquals(5, length)
    }

    @Test
    @DisplayName("최대 길이 제한 (65535 bytes)")
    fun `enforces maximum length limit`() {
        // 65535 바이트는 성공해야 함
        val maxValue = "A".repeat(65535)
        assertDoesNotThrow { encoder.encode(maxValue) }

        // 65536 바이트는 실패해야 함
        val tooLongValue = "A".repeat(65536)
        val exception = assertThrows(IllegalArgumentException::class.java) {
            encoder.encode(tooLongValue)
        }
        assertTrue(exception.message!!.contains("too long"))
    }

    @Test
    @DisplayName("Round-trip 테스트 - 다양한 문자열")
    fun `round trip test with various strings`() {
        val testCases = listOf(
            "",
            "a",
            "Hello, World!",
            "Line1\nLine2\nLine3",
            "Tab\tSeparated\tValues",
            "안녕 Hello こんにちは 你好",
            " Leading and trailing spaces ",
            "Special: !@#\$%^&*()",
            "A".repeat(1000)
        )

        testCases.forEach { original ->
            val encoded = encoder.encode(original)
            val (decoded, _) = encoder.decode(encoded, 0)
            assertEquals(original, decoded, "Round-trip failed for: $original")
        }
    }
}
