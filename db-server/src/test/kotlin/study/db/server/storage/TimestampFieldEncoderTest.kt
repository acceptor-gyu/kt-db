package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TimestampFieldEncoder 테스트")
class TimestampFieldEncoderTest {

    private val encoder = TimestampFieldEncoder()

    @Test
    @DisplayName("ISO-8601 형식 인코딩/디코딩")
    fun `encodes and decodes ISO-8601 format`() {
        val value = "2024-01-15T10:30:00Z"
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        assertEquals(value, decoded)
        assertEquals(8, consumed)
        assertEquals(8, encoded.size)
    }

    @Test
    @DisplayName("LocalDateTime 형식 인코딩/디코딩")
    fun `encodes and decodes LocalDateTime format`() {
        val value = "2024-01-15 10:30:00"
        val encoded = encoder.encode(value)
        val (decoded, consumed) = encoder.decode(encoded, 0)

        // 디코딩된 값은 항상 ISO-8601 형식으로 반환됨
        assertNotNull(decoded)
        assertEquals(8, consumed)
        assertTrue(decoded.contains("2024-01-15"))
    }

    @Test
    @DisplayName("다양한 ISO-8601 형식 인코딩")
    fun `encodes various ISO-8601 formats`() {
        val testCases = listOf(
            "2024-12-31T23:59:59Z",
            "2000-01-01T00:00:00Z",
            "2024-06-15T12:30:45Z"
        )

        testCases.forEach { value ->
            val encoded = encoder.encode(value)
            val (decoded, _) = encoder.decode(encoded, 0)
            assertEquals(value, decoded, "Failed for: $value")
        }
    }

    @Test
    @DisplayName("offset을 사용한 디코딩")
    fun `decodes with offset`() {
        val value = "2024-01-15T10:30:00Z"
        val encoded = encoder.encode(value)

        // 앞에 더미 데이터 추가
        val withPadding = ByteArray(10) + encoded
        val (decoded, consumed) = encoder.decode(withPadding, 10)

        assertEquals(value, decoded)
        assertEquals(8, consumed)
    }

    @Test
    @DisplayName("여러 timestamp 연속 디코딩")
    fun `decodes multiple timestamps sequentially`() {
        val values = listOf(
            "2024-01-15T10:30:00Z",
            "2024-02-20T14:45:00Z",
            "2024-03-25T18:00:00Z"
        )
        var currentBytes = ByteArray(0)

        // 모든 timestamp 인코딩하여 연결
        values.forEach { value ->
            currentBytes += encoder.encode(value)
        }

        assertEquals(24, currentBytes.size)  // 3 timestamps * 8 bytes

        // 순차적으로 디코딩
        var offset = 0
        values.forEach { expectedValue ->
            val (decoded, consumed) = encoder.decode(currentBytes, offset)
            assertEquals(expectedValue, decoded)
            assertEquals(8, consumed)
            offset += consumed
        }
    }

    @Test
    @DisplayName("Unix timestamp 값 검증")
    fun `verifies Unix timestamp value`() {
        val value = "1970-01-01T00:00:00Z"  // Unix epoch
        val encoded = encoder.encode(value)

        // Unix epoch는 0 밀리초여야 함
        val millis = java.nio.ByteBuffer.wrap(encoded)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .getLong()

        assertEquals(0L, millis)
    }

    @Test
    @DisplayName("최근 날짜 인코딩/디코딩")
    fun `encodes and decodes recent date`() {
        val value = "2024-01-15T10:30:00Z"
        val encoded = encoder.encode(value)

        // 2024년은 양수 밀리초 값이어야 함
        val millis = java.nio.ByteBuffer.wrap(encoded)
            .order(java.nio.ByteOrder.BIG_ENDIAN)
            .getLong()

        assertTrue(millis > 0)
        assertTrue(millis > 1_700_000_000_000L)  // 2023년 11월 이후
    }

    @Test
    @DisplayName("잘못된 형식 처리")
    fun `handles invalid format`() {
        val invalidFormats = listOf(
            "2024/01/15",
            "invalid",
            "",
            "2024-13-01T00:00:00Z",  // 잘못된 월
            "not a timestamp"
        )

        invalidFormats.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                encoder.encode(invalid)
            }
        }
    }

    @Test
    @DisplayName("Round-trip 테스트 - ISO-8601")
    fun `round trip test ISO-8601`() {
        val testCases = listOf(
            "2024-01-15T10:30:00Z",
            "2000-01-01T00:00:00Z",
            "2024-12-31T23:59:59Z",
            "2024-06-15T12:00:00Z"
        )

        testCases.forEach { original ->
            val encoded = encoder.encode(original)
            val (decoded, _) = encoder.decode(encoded, 0)
            assertEquals(original, decoded, "Round-trip failed for: $original")
        }
    }

    @Test
    @DisplayName("Round-trip 테스트 - LocalDateTime")
    fun `round trip test LocalDateTime`() {
        val value = "2024-01-15 10:30:00"
        val encoded = encoder.encode(value)
        val (decoded, _) = encoder.decode(encoded, 0)

        // 동일한 시점을 나타내는지 확인 (형식은 다를 수 있음)
        val originalInstant = java.time.LocalDateTime.parse(value.replace(" ", "T"))
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
        val decodedInstant = java.time.Instant.parse(decoded)

        assertEquals(originalInstant, decodedInstant)
    }

    @Test
    @DisplayName("인코딩 크기 검증")
    fun `verifies encoding size`() {
        val testCases = listOf(
            "2024-01-15T10:30:00Z",
            "2000-01-01T00:00:00Z",
            "1970-01-01T00:00:00Z"
        )

        testCases.forEach { value ->
            val encoded = encoder.encode(value)
            assertEquals(8, encoded.size, "Encoding size should always be 8 bytes for: $value")
        }
    }
}
