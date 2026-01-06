package study.db.storage_engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * InnoDB에서 컬럼 하나(INT, BIGINT 등)를 byte 단위로 변환하는 역할
 */
class IntFieldEncoder {

    /**
     * INT 값을 Big Endian 4 byte로 직렬화
     */
    fun encode(value: Int): ByteArray {
        val buffer = ByteBuffer
            .allocate(4)
            .order(ByteOrder.BIG_ENDIAN)

        buffer.putInt(value)
        return buffer.array()
    }

    /**
     * 4 byte를 INT 값으로 역직렬화
     */
    fun decode(bytes: ByteArray): Int {
        val buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)

        return buffer.int
    }
}