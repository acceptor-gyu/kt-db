package study.db.storage_engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RowEncoder 테스트")
class RowEncoderTest {

    private lateinit var rowEncoder: RowEncoder
    private lateinit var intFieldEncoder: IntFieldEncoder

    @BeforeEach
    fun setUp() {
        intFieldEncoder = IntFieldEncoder()
        rowEncoder = RowEncoder(intFieldEncoder)
    }

    @Nested
    @DisplayName("encodeRow() 테스트")
    inner class EncodeRowTest {

        @Test
        @DisplayName("단일 컬럼 인코딩")
        fun `encode single column`() {
            val values = intArrayOf(1)

            val result = rowEncoder.encodeRow(values)

            assertEquals(4, result.size)
        }

        @Test
        @DisplayName("다중 컬럼 인코딩")
        fun `encode multiple columns`() {
            val values = intArrayOf(1, 2, 3)

            val result = rowEncoder.encodeRow(values)

            assertEquals(12, result.size)
        }

        @Test
        @DisplayName("빈 배열 인코딩")
        fun `encode empty array`() {
            val values = intArrayOf()

            val result = rowEncoder.encodeRow(values)

            assertEquals(0, result.size)
        }

        @Test
        @DisplayName("음수 포함 인코딩")
        fun `encode with negative values`() {
            val values = intArrayOf(-1, 0, 1)

            val result = rowEncoder.encodeRow(values)

            assertEquals(12, result.size)
        }

        @Test
        @DisplayName("각 컬럼이 올바른 위치에 인코딩됨")
        fun `columns are encoded in correct order`() {
            val values = intArrayOf(1, 2)

            val result = rowEncoder.encodeRow(values)

            // 첫 번째 컬럼 (1) 검증
            val firstColumn = result.copyOfRange(0, 4)
            assertEquals(1, intFieldEncoder.decode(firstColumn))

            // 두 번째 컬럼 (2) 검증
            val secondColumn = result.copyOfRange(4, 8)
            assertEquals(2, intFieldEncoder.decode(secondColumn))
        }
    }

    @Nested
    @DisplayName("decodeRow() 테스트")
    inner class DecodeRowTest {

        @Test
        @DisplayName("단일 컬럼 디코딩")
        fun `decode single column`() {
            val encoded = rowEncoder.encodeRow(intArrayOf(42))

            val result = rowEncoder.decodeRow(encoded)

            assertEquals(1, result.size)
            assertEquals(42, result[0])
        }

        @Test
        @DisplayName("다중 컬럼 디코딩")
        fun `decode multiple columns`() {
            val encoded = rowEncoder.encodeRow(intArrayOf(1, 2, 3))

            val result = rowEncoder.decodeRow(encoded)

            assertEquals(3, result.size)
            assertArrayEquals(intArrayOf(1, 2, 3), result)
        }

        @Test
        @DisplayName("빈 배열 디코딩")
        fun `decode empty array`() {
            val encoded = byteArrayOf()

            val result = rowEncoder.decodeRow(encoded)

            assertEquals(0, result.size)
        }

        @Test
        @DisplayName("음수 포함 디코딩")
        fun `decode with negative values`() {
            val encoded = rowEncoder.encodeRow(intArrayOf(-100, 0, 100))

            val result = rowEncoder.decodeRow(encoded)

            assertArrayEquals(intArrayOf(-100, 0, 100), result)
        }
    }

    @Nested
    @DisplayName("왕복(Round-trip) 테스트")
    inner class RoundTripTest {

        @Test
        @DisplayName("encodeRow 후 decodeRow하면 원본 배열 복원")
        fun `encode then decode returns original array`() {
            val testCases = listOf(
                intArrayOf(),
                intArrayOf(0),
                intArrayOf(1, 2, 3),
                intArrayOf(-1, -2, -3),
                intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE),
                intArrayOf(1, -1, 0, 256, 65536)
            )

            for (original in testCases) {
                val encoded = rowEncoder.encodeRow(original)
                val decoded = rowEncoder.decodeRow(encoded)

                assertArrayEquals(original, decoded, "Failed for: ${original.contentToString()}")
            }
        }

        @Test
        @DisplayName("큰 Row 인코딩/디코딩")
        fun `encode and decode large row`() {
            val original = IntArray(100) { it }

            val encoded = rowEncoder.encodeRow(original)
            val decoded = rowEncoder.decodeRow(encoded)

            assertEquals(400, encoded.size)
            assertArrayEquals(original, decoded)
        }
    }
}
