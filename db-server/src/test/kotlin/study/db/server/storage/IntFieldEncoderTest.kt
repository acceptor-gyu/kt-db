package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("IntFieldEncoder 테스트")
class IntFieldEncoderTest {

    private lateinit var encoder: IntFieldEncoder

    @BeforeEach
    fun setUp() {
        encoder = IntFieldEncoder()
    }

    @Nested
    @DisplayName("encode() 테스트")
    inner class EncodeTest {

        @Test
        @DisplayName("양수를 Big Endian 4바이트로 인코딩")
        fun `encode positive number`() {
            val result = encoder.encode(123)

            assertEquals(4, result.size)
            // 123 = 0x0000007B in Big Endian
            assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x7B), result)
        }

        @Test
        @DisplayName("0을 인코딩")
        fun `encode zero`() {
            val result = encoder.encode(0)

            assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00, 0x00), result)
        }

        @Test
        @DisplayName("음수(-1)를 인코딩")
        fun `encode negative number`() {
            val result = encoder.encode(-1)

            // -1 = 0xFFFFFFFF in two's complement
            assertArrayEquals(
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                result
            )
        }

        @Test
        @DisplayName("Int.MAX_VALUE를 인코딩")
        fun `encode max int`() {
            val result = encoder.encode(Int.MAX_VALUE)

            // Int.MAX_VALUE = 0x7FFFFFFF
            assertArrayEquals(
                byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                result
            )
        }

        @Test
        @DisplayName("Int.MIN_VALUE를 인코딩")
        fun `encode min int`() {
            val result = encoder.encode(Int.MIN_VALUE)

            // Int.MIN_VALUE = 0x80000000
            assertArrayEquals(
                byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00),
                result
            )
        }
    }

    @Nested
    @DisplayName("decode() 테스트")
    inner class DecodeTest {

        @Test
        @DisplayName("양수 바이트 배열을 디코딩")
        fun `decode positive number`() {
            val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x7B)

            val result = encoder.decode(bytes)

            assertEquals(123, result)
        }

        @Test
        @DisplayName("0 바이트 배열을 디코딩")
        fun `decode zero`() {
            val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00)

            val result = encoder.decode(bytes)

            assertEquals(0, result)
        }

        @Test
        @DisplayName("음수(-1) 바이트 배열을 디코딩")
        fun `decode negative number`() {
            val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

            val result = encoder.decode(bytes)

            assertEquals(-1, result)
        }

        @Test
        @DisplayName("Int.MAX_VALUE 바이트 배열을 디코딩")
        fun `decode max int`() {
            val bytes = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

            val result = encoder.decode(bytes)

            assertEquals(Int.MAX_VALUE, result)
        }

        @Test
        @DisplayName("Int.MIN_VALUE 바이트 배열을 디코딩")
        fun `decode min int`() {
            val bytes = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00)

            val result = encoder.decode(bytes)

            assertEquals(Int.MIN_VALUE, result)
        }
    }

    @Nested
    @DisplayName("왕복(Round-trip) 테스트")
    inner class RoundTripTest {

        @Test
        @DisplayName("encode 후 decode하면 원본 값 복원")
        fun `encode then decode returns original value`() {
            val testValues = listOf(0, 1, -1, 123, -456, Int.MAX_VALUE, Int.MIN_VALUE, 256, 65536)

            for (value in testValues) {
                val encoded = encoder.encode(value)
                val decoded = encoder.decode(encoded)

                assertEquals(value, decoded, "Failed for value: $value")
            }
        }
    }
}
