package study.db.server.storage

/**
 * BooleanFieldEncoder - BOOLEAN 타입 필드 인코더
 *
 * 인코딩 형식: 1 byte
 * - 0x00: false
 * - 0x01: true
 */
class BooleanFieldEncoder : FieldEncoder {

    /**
     * BOOLEAN 값을 바이트 배열로 인코딩
     *
     * @param value 인코딩할 문자열 값 ("true" 또는 "false", 대소문자 무관)
     * @return 1-byte 배열 (0x00 = false, 0x01 = true)
     */
    override fun encode(value: String): ByteArray {
        val boolValue = value.toBoolean()
        return byteArrayOf(if (boolValue) 0x01 else 0x00)
    }

    /**
     * 바이트 배열에서 BOOLEAN 값을 디코딩
     *
     * @param bytes 전체 바이트 배열
     * @param offset 디코딩을 시작할 위치
     * @return Pair(디코딩된 불린 값, 소비한 바이트 수 = 1)
     */
    override fun decode(bytes: ByteArray, offset: Int): Pair<Boolean, Int> {
        val value = bytes[offset] != 0.toByte()
        return Pair(value, 1)
    }
}
