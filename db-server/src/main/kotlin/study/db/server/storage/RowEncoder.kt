package study.db.server.storage

import study.db.common.Row
import study.db.server.exception.UnsupportedTypeException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 하나의 Row를 바이트 배열로 인코딩/디코딩
 *
 * 지원하는 데이터 타입: INT, VARCHAR, TIMESTAMP, BOOLEAN
 *
 * 파일 형식:
 * [Row Length: 4 bytes]
 * [Deleted Flag: 1 byte]
 * [Version: 8 bytes]
 * [Field 1 Data]
 * [Field 2 Data]
 * ...
 */
class RowEncoder(
    private val intFieldEncoder: IntFieldEncoder,
    private val varcharFieldEncoder: VarcharFieldEncoder = VarcharFieldEncoder(),
    private val booleanFieldEncoder: BooleanFieldEncoder = BooleanFieldEncoder(),
    private val timestampFieldEncoder: TimestampFieldEncoder = TimestampFieldEncoder()
) {

    /**
     * Row 객체를 byte 배열로 인코딩 (deleted, version 포함)
     *
     * DELETE 연산에 사용됩니다. Tombstone 방식으로 deleted 플래그를 파일에 저장합니다.
     *
     * @param row Row 객체 (data, deleted, version 포함)
     * @param schema 테이블 스키마 (컬럼명 -> 타입)
     * @return 인코딩된 바이트 배열
     */
    fun encodeRow(row: Row, schema: Map<String, String>): ByteArray {
        val bufferList = mutableListOf<ByteArray>()
        var totalSize = 4 + 1 + 8  // Row length (4) + deleted (1) + version (8)

        // 스키마 순서대로 각 컬럼 인코딩
        schema.forEach { (columnName, typeName) ->
            val value = row.data[columnName]
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
        result.put(if (row.deleted) 1.toByte() else 0.toByte())  // Deleted flag
        result.putLong(row.version)  // Version
        bufferList.forEach { result.put(it) }
        return result.array()
    }

    /**
     * Map 형태의 행 데이터를 byte 배열로 인코딩
     *
     * Table.rows (타입: List<Map<String, String>>)를 파일에 저장할 때 사용됩니다.
     * 내부적으로 Row 객체로 변환하여 인코딩합니다 (deleted=false, version=0).
     *
     * @param row 인코딩할 행 데이터 (컬럼명 -> 값)
     * @param schema 테이블 스키마 (컬럼명 -> 타입)
     * @return 인코딩된 바이트 배열
     */
    fun encodeRow(row: Map<String, String>, schema: Map<String, String>): ByteArray {
        return encodeRow(Row(data = row, deleted = false, version = 0), schema)
    }

    /**
     * byte 배열에서 Row 객체 복원 (deleted, version 포함)
     *
     * 파일에서 행을 읽을 때 사용됩니다. deleted 플래그와 version을 포함한
     * 전체 Row 객체를 복원합니다.
     *
     * @param bytes 디코딩할 바이트 배열 (row length prefix 포함)
     * @param schema 테이블 스키마 (컬럼명 -> 타입)
     * @return 복원된 Row 객체
     */
    fun decodeRowObject(bytes: ByteArray, schema: Map<String, String>): Row {
        val data = mutableMapOf<String, String>()
        var offset = 4  // Row length prefix 건너뛰기

        // Deleted flag 읽기 (1 byte)
        val deleted = bytes[offset].toInt() != 0
        offset += 1

        // Version 읽기 (8 bytes)
        val versionBuffer = ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.BIG_ENDIAN)
        val version = versionBuffer.getLong()
        offset += 8

        // 각 필드 디코딩
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
            data[columnName] = value
            offset += consumed
        }

        return Row(data = data, deleted = deleted, version = version)
    }
}
