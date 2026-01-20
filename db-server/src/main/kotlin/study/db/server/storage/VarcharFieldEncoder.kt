package study.db.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VarcharFieldEncoder - VARCHAR 타입 필드 인코더
 *
 * 인코딩 형식: [2-byte length][UTF-8 bytes]
 * - Length: Big Endian으로 인코딩된 문자열 바이트 길이
 * - 최대 길이: 65535 바이트 (2^16 - 1)
 */
class VarcharFieldEncoder : FieldEncoder {

    /**
     * VARCHAR 값을 바이트 배열로 인코딩
     *
     * @param value 인코딩할 문자열 값
     * @return [2-byte length][UTF-8 bytes] 형식의 바이트 배열
     */
    override fun encode(value: String): ByteArray {
        val utf8Bytes = value.toByteArray(Charsets.UTF_8)

        require(utf8Bytes.size <= 65535) {
            "VARCHAR value too long: ${utf8Bytes.size} bytes (max 65535)"
        }

        val buffer = ByteBuffer.allocate(2 + utf8Bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(utf8Bytes.size.toShort())
        buffer.put(utf8Bytes)
        return buffer.array()
    }

    /**
     * 바이트 배열에서 VARCHAR 값을 디코딩
     *
     * @param bytes 전체 바이트 배열
     * @param offset 디코딩을 시작할 위치
     * @return Pair(디코딩된 문자열, 소비한 바이트 수)
     */
    override fun decode(bytes: ByteArray, offset: Int): Pair<String, Int> {
        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
            .order(ByteOrder.BIG_ENDIAN)

        val length = buffer.getShort().toInt()
        val stringBytes = ByteArray(length)
        buffer.get(stringBytes)

        val value = String(stringBytes, Charsets.UTF_8)
        return Pair(value, 2 + length)
    }
}
