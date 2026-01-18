package study.db.server.storage

/**
 * FieldEncoder - 단일 필드를 바이트 배열로 인코딩/디코딩하는 인터페이스
 *
 * 모든 데이터 타입(INT, VARCHAR, TIMESTAMP, BOOLEAN)에 대한
 * 인코더는 이 인터페이스를 구현해야 함
 */
interface FieldEncoder {
    /**
     * 문자열 값을 바이트 배열로 인코딩
     *
     * @param value 인코딩할 문자열 값
     * @return 인코딩된 바이트 배열
     */
    fun encode(value: String): ByteArray

    /**
     * 바이트 배열에서 값을 디코딩
     *
     * @param bytes 전체 바이트 배열
     * @param offset 디코딩을 시작할 위치
     * @return Pair(디코딩된 값, 소비한 바이트 수)
     */
    fun decode(bytes: ByteArray, offset: Int): Pair<Any, Int>
}
