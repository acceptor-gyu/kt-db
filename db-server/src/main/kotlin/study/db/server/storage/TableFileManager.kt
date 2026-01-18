package study.db.server.storage

import study.db.common.Table
import study.db.server.exception.UnsupportedTypeException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TableFileManager - 테이블 파일 기반 저장/로드 관리자
 *
 * 파일 형식:
 * - Header (24 bytes): Magic, Version, Row Count, Column Count, Schema Length, Reserved
 * - Schema Section: Column Name Length, Name, Type Tag
 * - Data Section: Row Length, Field Data
 *
 * 파일명: {tableName}.dat
 */
class TableFileManager(
    private val dataDirectory: File,
    private val rowEncoder: RowEncoder
) {
    init {
        if (!dataDirectory.exists()) {
            dataDirectory.mkdirs()
        }
    }

    /**
     * 테이블을 파일로 저장
     * Atomic write: temp file → sync → rename
     *
     * @param table 저장할 테이블
     */
    fun writeTable(table: Table) {
        val file = File(dataDirectory, "${table.tableName}.dat")
        val tempFile = File(dataDirectory, "${table.tableName}.dat.tmp")

        try {
            RandomAccessFile(tempFile, "rw").use { raf ->
                // Write header
                writeHeader(raf, table)

                // Write schema
                writeSchema(raf, table.dataType)

                // Write data rows
                table.rows.forEach { row ->
                    val rowBytes = rowEncoder.encodeRow(row, table.dataType)
                    raf.write(rowBytes)
                }

                // Ensure data is written to disk
                raf.fd.sync()
            }

            // Atomic rename
            if (file.exists()) file.delete()
            tempFile.renameTo(file)

        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * 파일 헤더 작성 (24 bytes)
     */
    private fun writeHeader(raf: RandomAccessFile, table: Table) {
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(0xDBF0.toShort())  // Magic number
        buffer.putShort(1)  // Version
        buffer.putLong(table.rows.size.toLong())  // Row count
        buffer.putInt(table.dataType.size)  // Column count

        // Calculate schema length
        val schemaLength = table.dataType.entries.sumOf { (name, _) ->
            2 + name.toByteArray(Charsets.UTF_8).size + 1
        }
        buffer.putInt(schemaLength)
        buffer.putInt(0)  // Reserved

        raf.write(buffer.array())
    }

    /**
     * 스키마 섹션 작성
     */
    private fun writeSchema(raf: RandomAccessFile, schema: Map<String, String>) {
        schema.forEach { (columnName, typeName) ->
            val nameBytes = columnName.toByteArray(Charsets.UTF_8)
            val buffer = ByteBuffer.allocate(2 + nameBytes.size + 1)
                .order(ByteOrder.BIG_ENDIAN)

            buffer.putShort(nameBytes.size.toShort())
            buffer.put(nameBytes)

            val typeTag = when (typeName.uppercase()) {
                "INT" -> 0x01.toByte()
                "VARCHAR" -> 0x02.toByte()
                "TIMESTAMP" -> 0x03.toByte()
                "BOOLEAN" -> 0x04.toByte()
                else -> throw UnsupportedTypeException("Unsupported type: $typeName")
            }
            buffer.put(typeTag)

            raf.write(buffer.array())
        }
    }

    /**
     * 파일에서 테이블 읽기
     *
     * @param fileName 테이블 이름 (확장자 제외)
     * @return 읽은 테이블 또는 null (파일 없음)
     */
    fun readTable(fileName: String): Table? {
        val file = File(dataDirectory, "$fileName.dat")
        if (!file.exists()) return null

        try {
            return RandomAccessFile(file, "r").use { raf ->
                // Read and validate header
                val (rowCount, columnCount) = readHeader(raf)

                // Read schema
                val schema = readSchema(raf, columnCount)

                // Read data rows
                val rows = mutableListOf<Map<String, String>>()
                for (i in 0 until rowCount) {
                    val rowBytes = readRowBytes(raf)
                    val row = rowEncoder.decodeRow(rowBytes, schema)
                    rows.add(row)
                }

                Table(fileName, schema, rows)
            }
        } catch (e: Exception) {
            throw IOException("Failed to read table $fileName", e)
        }
    }

    /**
     * 파일 헤더 읽기 및 검증
     */
    private fun readHeader(raf: RandomAccessFile): Pair<Long, Int> {
        val headerBytes = ByteArray(24)
        raf.readFully(headerBytes)

        val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val magic = buffer.getShort()
        if (magic != 0xDBF0.toShort()) {
            throw IOException("Invalid file format (bad magic number: 0x${magic.toString(16)})")
        }

        val version = buffer.getShort()
        if (version != 1.toShort()) {
            throw IOException("Unsupported file version: $version")
        }

        val rowCount = buffer.getLong()
        val columnCount = buffer.getInt()
        buffer.getInt()  // Skip schema length
        buffer.getInt()  // Skip reserved

        return Pair(rowCount, columnCount)
    }

    /**
     * 스키마 섹션 읽기
     */
    private fun readSchema(raf: RandomAccessFile, columnCount: Int): Map<String, String> {
        val schema = LinkedHashMap<String, String>()  // Preserve order

        for (i in 0 until columnCount) {
            val lengthBytes = ByteArray(2)
            raf.readFully(lengthBytes)
            val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getShort().toInt()

            val nameBytes = ByteArray(length)
            raf.readFully(nameBytes)
            val columnName = String(nameBytes, Charsets.UTF_8)

            val typeTag = raf.readByte()
            val typeName = when (typeTag.toInt()) {
                0x01 -> "INT"
                0x02 -> "VARCHAR"
                0x03 -> "TIMESTAMP"
                0x04 -> "BOOLEAN"
                else -> throw IOException("Unknown type tag: 0x${typeTag.toString(16)}")
            }

            schema[columnName] = typeName
        }

        return schema
    }

    /**
     * 행 데이터 읽기 (row length prefix 포함)
     */
    private fun readRowBytes(raf: RandomAccessFile): ByteArray {
        val lengthBytes = ByteArray(4)
        raf.readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.BIG_ENDIAN).getInt()

        val rowBytes = ByteArray(4 + length)
        System.arraycopy(lengthBytes, 0, rowBytes, 0, 4)
        raf.readFully(rowBytes, 4, length)

        return rowBytes
    }

    /**
     * 테이블 파일 삭제
     *
     * @param tableName 테이블 이름
     * @return 삭제 성공 여부
     */
    fun deleteTable(tableName: String): Boolean {
        val file = File(dataDirectory, "$tableName.dat")
        return file.delete()
    }

    /**
     * 모든 테이블 목록 조회
     *
     * @return 테이블 이름 리스트
     */
    fun listAllTables(): List<String> {
        return dataDirectory.listFiles { _, name ->
            name.endsWith(".dat") && !name.endsWith(".tmp")
        }?.map { it.nameWithoutExtension } ?: emptyList()
    }
}
