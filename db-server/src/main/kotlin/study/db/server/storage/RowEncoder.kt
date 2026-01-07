package study.db.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 하나의 Row(Recorder)를 구성
 * 여러 Field를 순서대로 byte buffer에 배치
 */
class RowEncoder(
    private val intFieldEncoder: IntFieldEncoder
) {

    /**
     * INT 컬럼 여러 개를 하나의 Row(byte 배열)로 변환
     */
    fun encodeRow(values: IntArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(values.size * 4)
            .order(ByteOrder.BIG_ENDIAN)

        for (value in values) {
            buffer.put(intFieldEncoder.encode(value))
        }

        return buffer.array()
    }

    /**
     * Row(byte 배열)를 INT 배열로 복원
     */
    fun decodeRow(bytes: ByteArray): IntArray {
        val count = bytes.size / 4
        val result = IntArray(count)

        for (i in 0 until count) {
            val offset = i * 4
            val fieldBytes = bytes.copyOfRange(offset, offset + 4)
            result[i] = intFieldEncoder.decode(fieldBytes)
        }

        return result
    }
}
