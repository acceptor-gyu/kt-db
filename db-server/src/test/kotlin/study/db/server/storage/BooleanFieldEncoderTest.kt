package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BooleanFieldEncoder 테스트")
class BooleanFieldEncoderTest {

    private val encoder = BooleanFieldEncoder()

    @Test
    @DisplayName("true 인코딩/디코딩")
    fun `encodes and decodes true`() {
        val value = "true"
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        assertEquals(true, decoded)
        assertEquals(1, consumed)
        assertEquals(1, encoded.size)
        assertEquals(0x01.toByte(), encoded[0])
    }

    @Test
    @DisplayName("false 인코딩/디코딩")
    fun `encodes and decodes false`() {
        val value = "false"
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        assertEquals(false, decoded)
        assertEquals(1, consumed)
        assertEquals(1, encoded.size)
        assertEquals(0x00.toByte(), encoded[0])
    }

    @Test
    @DisplayName("대소문자 무관 true 인코딩")
    fun `encodes true case insensitively`() {
        val testCases = listOf("true", "TRUE", "True", "TrUe")

        testCases.forEach { value ->
            val encoded = encoder.encode(value)
            assertEquals(0x01.toByte(), encoded[0], "Failed for: $value")
        }
    }

    @Test
    @DisplayName("대소문자 무관 false 인코딩")
    fun `encodes false case insensitively`() {
        val testCases = listOf("false", "FALSE", "False", "FaLsE")

        testCases.forEach { value ->
            val encoded = encoder.encode(value)
            assertEquals(0x00.toByte(), encoded[0], "Failed for: $value")
        }
    }

    @Test
    @DisplayName("offset을 사용한 디코딩")
    fun `decodes with offset`() {
        val value = "true"
        val encoded = encoder.encode(value)

        // 앞에 더미 데이터 추가
        val withPadding = ByteArray(5) { 0xFF.toByte() } + encoded
        val (decoded, consumed) = encoder.decode(withPadding, 5)

        assertEquals(true, decoded)
        assertEquals(1, consumed)
    }

    @Test
    @DisplayName("여러 boolean 연속 디코딩")
    fun `decodes multiple booleans sequentially`() {
        val values = listOf(true, false, true, true, false)
        var currentBytes = ByteArray(0)

        // 모든 값 인코딩하여 연결
        values.forEach { value ->
            currentBytes += encoder.encode(value.toString())
        }

        assertEquals(5, currentBytes.size)

        // 순차적으로 디코딩
        var offset = 0
        values.forEach { expectedValue ->
            val (decoded, consumed) = encoder.decode(currentBytes, offset)
            assertEquals(expectedValue, decoded)
            assertEquals(1, consumed)
            offset += consumed
        }
    }

    @Test
    @DisplayName("인코딩 바이트 값 검증")
    fun `verifies encoded byte values`() {
        val trueEncoded = encoder.encode("true")
        val falseEncoded = encoder.encode("false")

        assertEquals(0x01.toByte(), trueEncoded[0])
        assertEquals(0x00.toByte(), falseEncoded[0])
    }

    @Test
    @DisplayName("디코딩 바이트 값 검증")
    fun `verifies decoded values from bytes`() {
        val trueBytes = byteArrayOf(0x01)
        val falseBytes = byteArrayOf(0x00)

        val (trueDecoded, _) = encoder.decode(trueBytes, 0)
        val (falseDecoded, _) = encoder.decode(falseBytes, 0)

        assertEquals(true, trueDecoded)
        assertEquals(false, falseDecoded)
    }

    @Test
    @DisplayName("Round-trip 테스트")
    fun `round trip test`() {
        val testCases = listOf("true", "false", "TRUE", "FALSE", "True", "False")

        testCases.forEach { original ->
            val encoded = encoder.encode(original)
            val (decoded, _) = encoder.decode(encoded, 0)
            assertEquals(original.toBoolean(), decoded, "Round-trip failed for: $original")
        }
    }

    @Test
    @DisplayName("비정상 문자열은 false로 처리")
    fun `handles invalid strings as false`() {
        // Kotlin의 toBoolean()은 "true" (대소문자 무관) 이외는 모두 false 반환
        val invalidValues = listOf("yes", "no", "1", "0", "", "maybe", "YES", "NO")

        invalidValues.forEach { value ->
            val encoded = encoder.encode(value)
            assertEquals(0x00.toByte(), encoded[0], "Should encode as false for: $value")
        }
    }
}
