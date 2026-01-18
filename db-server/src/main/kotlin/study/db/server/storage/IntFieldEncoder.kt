package study.db.server.storage

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * INT 타입 필드 인코더
 *
 * InnoDB에서 컬럼 하나(INT, BIGINT 등)를 byte 단위로 변환하는 역할
 * FieldEncoder 인터페이스를 구현하여 다른 타입 인코더들과 일관된 API 제공
 */
class IntFieldEncoder : FieldEncoder {

    /**
     * INT 값을 Big Endian 4 byte로 직렬화
     *
     * @param value String 형태의 INT 값 (예: "123")
     * @return 4 byte 배열
     */
    override fun encode(value: String): ByteArray {
        val intValue = value.toInt()
        return encode(intValue)
    }

    /**
     * INT 값을 Big Endian 4 byte로 직렬화 (기존 메서드, 하위 호환성 유지)
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
     *
     * @param bytes 바이트 배열
     * @param offset 읽기 시작 위치
     * @return Pair(INT 값, 읽은 바이트 수)
     */
    override fun decode(bytes: ByteArray, offset: Int): Pair<Int, Int> {
        val buffer = ByteBuffer
            .wrap(bytes, offset, 4)
            .order(ByteOrder.BIG_ENDIAN)

        val value = buffer.int
        return Pair(value, 4)
    }

    /**
     * 4 byte를 INT 값으로 역직렬화 (기존 메서드, 하위 호환성 유지)
     */
    fun decode(bytes: ByteArray): Int {
        val buffer = ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.BIG_ENDIAN)

        return buffer.int
    }
}
