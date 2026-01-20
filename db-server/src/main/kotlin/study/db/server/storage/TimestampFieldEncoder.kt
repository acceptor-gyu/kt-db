package study.db.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TimestampFieldEncoder - TIMESTAMP 타입 필드 인코더
 *
 * 인코딩 형식: 8-byte Unix timestamp (밀리초)
 * - Big Endian long 값 (Unix epoch 이후 경과한 밀리초)
 *
 * 지원 형식:
 * - ISO-8601: "2024-01-15T10:30:00Z"
 * - LocalDateTime: "2024-01-15 10:30:00"
 */
class TimestampFieldEncoder : FieldEncoder {

    /**
     * TIMESTAMP 값을 바이트 배열로 인코딩
     *
     * @param value 인코딩할 타임스탬프 문자열 (ISO-8601 또는 LocalDateTime 형식)
     * @return 8-byte Big Endian long 값 (Unix timestamp milliseconds)
     */
    override fun encode(value: String): ByteArray {
        // TypeValidator와 동일한 파싱 로직 사용
        val instant = try {
            java.time.Instant.parse(value)
        } catch (e: Exception) {
            // LocalDateTime 형식 시도 ("2024-01-15 10:30:00" -> "2024-01-15T10:30:00")
            try {
                java.time.LocalDateTime.parse(value.replace(" ", "T"))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
            } catch (e2: Exception) {
                throw IllegalArgumentException(
                    "Invalid TIMESTAMP format: $value. Expected ISO-8601 or LocalDateTime format",
                    e2
                )
            }
        }

        val millis = instant.toEpochMilli()
        return ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(millis)
            .array()
    }

    /**
     * 바이트 배열에서 TIMESTAMP 값을 디코딩
     *
     * @param bytes 전체 바이트 배열
     * @param offset 디코딩을 시작할 위치
     * @return Pair(ISO-8601 형식 타임스탬프 문자열, 소비한 바이트 수 = 8)
     */
    override fun decode(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val buffer = ByteBuffer.wrap(bytes, offset, 8)
            .order(ByteOrder.BIG_ENDIAN)
        val millis = buffer.getLong()
        val instant = java.time.Instant.ofEpochMilli(millis)
        val value = instant.toString()
        return Pair(value, 8)
    }
}
