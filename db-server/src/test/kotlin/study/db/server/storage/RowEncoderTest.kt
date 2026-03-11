package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RowEncoder 테스트")
class RowEncoderTest {

    @Nested
    @DisplayName("Multi-Type Row 인코딩/디코딩 테스트")
    inner class MultiTypeRowTest {
        private val varcharFieldEncoder = VarcharFieldEncoder()
        private val booleanFieldEncoder = BooleanFieldEncoder()
        private val timestampFieldEncoder = TimestampFieldEncoder()
        private val rowEncoder = RowEncoder(
            IntFieldEncoder(),
            varcharFieldEncoder,
            booleanFieldEncoder,
            timestampFieldEncoder
        )

        @Test
        @DisplayName("모든 타입을 포함한 Row 인코딩/디코딩")
        fun `encode and decode row with all types`() {
            val schema = mapOf(
                "id" to "INT",
                "name" to "VARCHAR",
                "active" to "BOOLEAN",
                "created_at" to "TIMESTAMP"
            )
            val row = mapOf(
                "id" to "123",
                "name" to "John Doe",
                "active" to "true",
                "created_at" to "2024-01-15T10:30:00Z"
            )

            val encoded = rowEncoder.encodeRow(row, schema)
            val decoded = rowEncoder.decodeRowObject(encoded, schema)

            assertEquals("123", decoded.data["id"])
            assertEquals("John Doe", decoded.data["name"])
            assertEquals("true", decoded.data["active"])
            assertEquals("2024-01-15T10:30:00Z", decoded.data["created_at"])
            assertFalse(decoded.deleted)
            assertEquals(0L, decoded.version)
        }

        @Test
        @DisplayName("VARCHAR만 있는 Row 인코딩/디코딩")
        fun `encode and decode row with VARCHAR only`() {
            val schema = mapOf(
                "name" to "VARCHAR",
                "email" to "VARCHAR"
            )
            val row = mapOf(
                "name" to "Jane",
                "email" to "jane@example.com"
            )

            val encoded = rowEncoder.encodeRow(row, schema)
            val decoded = rowEncoder.decodeRowObject(encoded, schema)

            assertEquals("Jane", decoded.data["name"])
            assertEquals("jane@example.com", decoded.data["email"])
        }

        @Test
        @DisplayName("여러 INT와 VARCHAR 혼합 Row")
        fun `encode and decode mixed INT and VARCHAR row`() {
            val schema = mapOf(
                "id" to "INT",
                "age" to "INT",
                "name" to "VARCHAR",
                "city" to "VARCHAR"
            )
            val row = mapOf(
                "id" to "1",
                "age" to "30",
                "name" to "Alice",
                "city" to "Seoul"
            )

            val encoded = rowEncoder.encodeRow(row, schema)
            val decoded = rowEncoder.decodeRowObject(encoded, schema)

            assertEquals("1", decoded.data["id"])
            assertEquals("30", decoded.data["age"])
            assertEquals("Alice", decoded.data["name"])
            assertEquals("Seoul", decoded.data["city"])
        }

        @Test
        @DisplayName("빈 VARCHAR 값 처리")
        fun `handles empty VARCHAR values`() {
            val schema = mapOf(
                "name" to "VARCHAR",
                "description" to "VARCHAR"
            )
            val row = mapOf(
                "name" to "Test",
                "description" to ""
            )

            val encoded = rowEncoder.encodeRow(row, schema)
            val decoded = rowEncoder.decodeRowObject(encoded, schema)

            assertEquals("Test", decoded.data["name"])
            assertEquals("", decoded.data["description"])
        }

        @Test
        @DisplayName("컬럼 누락 시 예외 발생")
        fun `throws exception when column is missing`() {
            val schema = mapOf(
                "id" to "INT",
                "name" to "VARCHAR"
            )
            val row = mapOf(
                "id" to "1"
                // name 누락
            )

            assertThrows(IllegalArgumentException::class.java) {
                rowEncoder.encodeRow(row, schema)
            }
        }

        @Test
        @DisplayName("Row length prefix와 메타데이터 검증")
        fun `verifies row length prefix with metadata`() {
            val schema = mapOf("id" to "INT")
            val row = mapOf("id" to "123")

            val encoded = rowEncoder.encodeRow(row, schema)

            // First 4 bytes should be row length (excluding the length field itself)
            val rowLength = java.nio.ByteBuffer.wrap(encoded)
                .order(java.nio.ByteOrder.BIG_ENDIAN)
                .getInt()

            // Row length = deleted (1) + version (8) + INT field (4) = 13 bytes
            assertEquals(13, rowLength)
            // Total size = 4-byte length + 13-byte data = 17 bytes
            assertEquals(17, encoded.size)
        }

        @Test
        @DisplayName("deleted와 version 정보 저장")
        fun `stores deleted and version information`() {
            val schema = mapOf("id" to "INT")
            val row = study.db.common.Row(
                data = mapOf("id" to "123"),
                deleted = true,
                version = 5L
            )

            val encoded = rowEncoder.encodeRow(row, schema)
            val decoded = rowEncoder.decodeRowObject(encoded, schema)

            assertEquals("123", decoded.data["id"])
            assertTrue(decoded.deleted)
            assertEquals(5L, decoded.version)
        }
    }
}
