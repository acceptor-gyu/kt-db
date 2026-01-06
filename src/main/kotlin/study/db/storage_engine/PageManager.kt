package study.db.storage_engine

import BYTE_LENGTH
import PAGE_SIZE
import java.io.RandomAccessFile

class PageManager(
    private val file: RandomAccessFile
) {
    private val pages: Array<Page?> = arrayOfNulls(BYTE_LENGTH / PAGE_SIZE)

    /**
     * 특정 Page 번호의 데이터 반환
     * =~ Cache Hit
     */
    fun readAt(id: Int): ByteArray {
        return pages[id]!!.data
    }

    /**
     * 파일 전체를 Page 단위로 읽어서 메모리에 적재
     * =~ Sequential IO
     */
    fun readAll() {
        for (i in pages.indices) {
            val buf = ByteArray(PAGE_SIZE)
            file.seek((i * PAGE_SIZE).toLong())
            file.readFully(buf)

            pages[i] = Page(
                id = i,
                data = buf
            )
        }
    }
}