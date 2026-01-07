package study.db.server.storage

import java.io.RandomAccessFile

class PageManager(
    private val file: RandomAccessFile
) {
    private val pages: MutableList<Page> = mutableListOf()

    /**
     * 특정 Page 번호의 데이터 반환
     * =~ Cache Hit
     */
    fun readAt(id: Int): ByteArray {
        require(id in pages.indices) {
            "Page id $id is out of bounds (0..${pages.size - 1})"
        }
        return pages[id].data
    }

    /**
     * 파일 전체를 Page 단위로 읽어서 메모리에 적재
     * =~ Sequential IO
     */
    fun readAll() {
        pages.clear()

        val fileLength = file.length()
        val pageCount = ((fileLength + PAGE_SIZE - 1) / PAGE_SIZE).toInt()

        for (i in 0 until pageCount) {
            val offset = i * PAGE_SIZE
            val remaining = (fileLength - offset).coerceAtMost(PAGE_SIZE.toLong()).toInt()
            val buf = ByteArray(PAGE_SIZE)

            file.seek(offset.toLong())
            file.read(buf, 0, remaining)

            pages.add(Page(id = i, data = buf))
        }
    }

    /**
     * 현재 로드된 페이지 수 반환
     */
    fun pageCount(): Int = pages.size

    /**
     * 페이지가 로드되었는지 확인
     */
    fun isLoaded(): Boolean = pages.isNotEmpty()
}
