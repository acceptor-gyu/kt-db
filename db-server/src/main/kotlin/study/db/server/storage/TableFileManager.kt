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
     * 파일에서 테이블 읽기 (readPage 기반)
     *
     * 모든 페이지를 순회하며 rows를 수집합니다.
     * 이제 readPage가 유일한 디스크 I/O 지점입니다.
     *
     * @param fileName 테이블 이름 (확장자 제외)
     * @return 읽은 테이블 또는 null (파일 없음)
     */
    fun readTable(fileName: String): Table? {
        // 1. 파일 존재 여부 확인
        val file = File(dataDirectory, "$fileName.dat")
        if (!file.exists()) return null

        try {
            // 2. 스키마 정보 읽기
            val schema = readTableSchema(fileName) ?: return null

            // 3. 페이지 수 확인
            val pageCount = getPageCount(fileName)
            if (pageCount == 0) {
                // 빈 테이블
                return Table(fileName, schema, emptyList())
            }

            // 4. 모든 페이지를 순회하며 rows 수집
            val allRows = mutableListOf<Map<String, String>>()
            for (pageNumber in 0 until pageCount) {
                val page = readPage(fileName, pageNumber) ?: continue
                val rows = decodePageToRows(page, schema)
                allRows.addAll(rows)
            }

            return Table(fileName, schema, allRows)
        } catch (e: Exception) {
            throw IOException("Failed to read table $fileName", e)
        }
    }

    /**
     * 테이블의 스키마만 읽기
     *
     * @param tableName 테이블 이름
     * @return 스키마 (컬럼명 -> 타입) 또는 null
     */
    private fun readTableSchema(tableName: String): Map<String, String>? {
        val file = File(dataDirectory, "$tableName.dat")
        if (!file.exists()) return null

        try {
            return RandomAccessFile(file, "r").use { raf ->
                val (_, columnCount) = readHeader(raf)
                readSchema(raf, columnCount)
            }
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Page 데이터를 Row 리스트로 디코딩
     *
     * @param page 페이지 객체
     * @param schema 테이블 스키마
     * @return Row 리스트
     */
    private fun decodePageToRows(page: Page, schema: Map<String, String>): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        var offset = 0
        val pageData = page.data

        try {
            while (offset < pageData.size) {
                // Row length 읽기 (4 bytes)
                if (offset + 4 > pageData.size) break

                val lengthBuffer = ByteBuffer.wrap(pageData, offset, 4).order(ByteOrder.BIG_ENDIAN)
                val rowLength = lengthBuffer.getInt()

                if (rowLength <= 0 || offset + 4 + rowLength > pageData.size) break

                // Row bytes 추출 (length prefix 포함)
                val rowBytes = ByteArray(4 + rowLength)
                System.arraycopy(pageData, offset, rowBytes, 0, 4 + rowLength)

                // Decode row
                val row = rowEncoder.decodeRow(rowBytes, schema)
                rows.add(row)

                offset += 4 + rowLength
            }
        } catch (e: Exception) {
            // 파싱 오류 시 현재까지 디코딩한 rows 반환
        }

        return rows
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

    /**
     * 특정 페이지만 읽기 (Buffer Pool용)
     *
     * @param tableName 테이블 이름
     * @param pageNumber 페이지 번호 (0부터 시작)
     * @return Page 객체 또는 null (페이지가 존재하지 않을 경우)
     */
    fun readPage(tableName: String, pageNumber: Int): Page? {
        val file = File(dataDirectory, "$tableName.dat")
        if (!file.exists()) return null

        try {
            return RandomAccessFile(file, "r").use { raf ->
                // 1. Header + Schema 크기 계산
                val (rowCount, columnCount) = readHeader(raf)
                val schema = readSchema(raf, columnCount)
                val metadataSize = calculateMetadataSize(schema)

                // 2. 데이터 섹션의 시작 위치
                val dataStartOffset = metadataSize

                // 3. Page offset 계산
                val pageOffset = dataStartOffset + (pageNumber * Page.PAGE_SIZE)

                // 4. 파일 크기 확인
                if (pageOffset >= file.length()) return@use null

                // 5. Page 위치로 seek
                raf.seek(pageOffset)

                // 6. 16KB 읽기 (또는 남은 크기만큼)
                val remaining = (file.length() - pageOffset).coerceAtMost(Page.PAGE_SIZE.toLong()).toInt()
                val pageData = ByteArray(Page.PAGE_SIZE)
                val bytesRead = raf.read(pageData, 0, remaining)

                if (bytesRead <= 0) return@use null

                // 7. 이 페이지의 레코드 수 계산 (간단 버전)
                val recordCount = countRecordsInPageData(pageData, bytesRead, schema)

                Page(
                    pageId = PageId(tableName, pageNumber),
                    data = pageData.copyOf(bytesRead),
                    recordCount = recordCount,
                    freeSpaceOffset = Page.HEADER_SIZE + bytesRead
                )
            }
        } catch (e: Exception) {
            throw IOException("Failed to read page $pageNumber from table $tableName", e)
        }
    }

    /**
     * 테이블의 페이지 수 계산
     *
     * @param tableName 테이블 이름
     * @return 페이지 수 (0이면 파일 없음)
     */
    fun getPageCount(tableName: String): Int {
        val file = File(dataDirectory, "$tableName.dat")
        if (!file.exists()) return 0

        try {
            return RandomAccessFile(file, "r").use { raf ->
                val (_, columnCount) = readHeader(raf)
                val schema = readSchema(raf, columnCount)
                val metadataSize = calculateMetadataSize(schema)

                val dataSize = file.length() - metadataSize
                val pageCount = ((dataSize + Page.PAGE_SIZE - 1) / Page.PAGE_SIZE).toInt()

                pageCount.coerceAtLeast(0)
            }
        } catch (e: Exception) {
            return 0
        }
    }

    /**
     * 메타데이터 크기 계산 (Header + Schema)
     */
    private fun calculateMetadataSize(schema: Map<String, String>): Long {
        val headerSize = 24L
        val schemaSize = schema.entries.sumOf { (name, _) ->
            2 + name.toByteArray(Charsets.UTF_8).size + 1
        }
        return headerSize + schemaSize
    }

    /**
     * 페이지 데이터 내의 레코드 수 카운트
     *
     * 현재는 간단하게 구현 (나중에 개선 가능)
     */
    private fun countRecordsInPageData(
        pageData: ByteArray,
        validBytes: Int,
        schema: Map<String, String>
    ): Int {
        var count = 0
        var offset = 0

        try {
            while (offset < validBytes) {
                // Row length 읽기 (4 bytes)
                if (offset + 4 > validBytes) break

                val buffer = ByteBuffer.wrap(pageData, offset, 4).order(ByteOrder.BIG_ENDIAN)
                val rowLength = buffer.getInt()

                if (rowLength <= 0 || offset + 4 + rowLength > validBytes) break

                count++
                offset += 4 + rowLength
            }
        } catch (e: Exception) {
            // 파싱 오류 시 현재까지 카운트한 수 반환
        }

        return count
    }
}
