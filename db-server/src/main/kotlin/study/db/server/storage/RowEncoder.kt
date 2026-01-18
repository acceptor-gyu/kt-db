package study.db.server.storage

import study.db.server.exception.UnsupportedTypeException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 하나의 Row(Recorder)를 구성
 * 여러 Field를 순서대로 byte buffer에 배치
 *
 * 지원하는 데이터 타입: INT, VARCHAR, TIMESTAMP, BOOLEAN
 */
class RowEncoder(
    private val intFieldEncoder: IntFieldEncoder,
    private val varcharFieldEncoder: VarcharFieldEncoder = VarcharFieldEncoder(),
    private val booleanFieldEncoder: BooleanFieldEncoder = BooleanFieldEncoder(),
    private val timestampFieldEncoder: TimestampFieldEncoder = TimestampFieldEncoder()
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

    /**
     * 여러 타입의 컬럼을 가진 Row를 byte 배열로 인코딩
     *
     * @param row 인코딩할 행 데이터 (컬럼명 -> 값)
     * @param schema 테이블 스키마 (컬럼명 -> 타입)
     * @return [Row Length: 4 bytes][Field 1 Data][Field 2 Data]... 형식의 바이트 배열
     */
    fun encodeRow(row: Map<String, String>, schema: Map<String, String>): ByteArray {
        val bufferList = mutableListOf<ByteArray>()
        var totalSize = 4  // Row length prefix

        // 스키마 순서대로 각 컬럼 인코딩
        schema.forEach { (columnName, typeName) ->
            val value = row[columnName]
                ?: throw IllegalArgumentException("Missing value for column: $columnName")

            val encoded = when (typeName.uppercase()) {
                "INT" -> intFieldEncoder.encode(value.toInt())
                "VARCHAR" -> varcharFieldEncoder.encode(value)
                "BOOLEAN" -> booleanFieldEncoder.encode(value)
                "TIMESTAMP" -> timestampFieldEncoder.encode(value)
                else -> throw UnsupportedTypeException("Unsupported type: $typeName")
            }
            bufferList.add(encoded)
            totalSize += encoded.size
        }

        // 모든 버퍼를 row length prefix와 함께 결합
        val result = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        result.putInt(totalSize - 4)  // Row length (length field 자체는 제외)
        bufferList.forEach { result.put(it) }
        return result.array()
    }

    /**
     * byte 배열에서 여러 타입의 컬럼을 가진 Row를 복원
     *
     * @param bytes 디코딩할 바이트 배열 (row length prefix 포함)
     * @param schema 테이블 스키마 (컬럼명 -> 타입)
     * @return 복원된 행 데이터 (컬럼명 -> 값)
     */
    fun decodeRow(bytes: ByteArray, schema: Map<String, String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var offset = 4  // Row length prefix 건너뛰기

        schema.forEach { (columnName, typeName) ->
            val (value, consumed) = when (typeName.uppercase()) {
                "INT" -> {
                    val fieldBytes = bytes.copyOfRange(offset, offset + 4)
                    Pair(intFieldEncoder.decode(fieldBytes).toString(), 4)
                }
                "VARCHAR" -> varcharFieldEncoder.decode(bytes, offset)
                "BOOLEAN" -> booleanFieldEncoder.decode(bytes, offset).let {
                    Pair(it.first.toString(), it.second)
                }
                "TIMESTAMP" -> timestampFieldEncoder.decode(bytes, offset)
                else -> throw UnsupportedTypeException("Unsupported type: $typeName")
            }
            result[columnName] = value
            offset += consumed
        }

        return result
    }
}
