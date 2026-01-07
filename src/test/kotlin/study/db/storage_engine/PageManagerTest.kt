package study.db.storage_engine

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.io.RandomAccessFile

@DisplayName("PageManager 테스트")
class PageManagerTest {

    private lateinit var tempFile: File
    private lateinit var randomAccessFile: RandomAccessFile
    private lateinit var pageManager: PageManager

    // 테스트에서 사용할 전체 바이트 길이
    private val totalByteLength = DEFAULT_PAGE_COUNT * PAGE_SIZE

    companion object {
        private const val RESOURCE_PATH = "/storage_engine"
    }

    @BeforeEach
    fun setUp() {
        // 임시 파일 생성
        tempFile = File.createTempFile("page_manager_test", ".dat")

        // totalByteLength 크기의 테스트 데이터 작성
        RandomAccessFile(tempFile, "rw").use { file ->
            for (i in 0 until totalByteLength) {
                file.writeByte(i % 256)
            }
        }

        randomAccessFile = RandomAccessFile(tempFile, "r")
        pageManager = PageManager(randomAccessFile)
    }

    @AfterEach
    fun tearDown() {
        randomAccessFile.close()
        tempFile.delete()
    }

    @Nested
    @DisplayName("readAll() 테스트")
    inner class ReadAllTest {

        @Test
        @DisplayName("파일 전체를 Page 단위로 읽기")
        fun `read entire file into pages`() {
            pageManager.readAll()

            // 예외 없이 모든 페이지 접근 가능
            for (i in 0 until DEFAULT_PAGE_COUNT) {
                assertNotNull(pageManager.readAt(i))
            }
        }

        @Test
        @DisplayName("각 페이지가 PAGE_SIZE 크기를 가짐")
        fun `each page has correct size`() {
            pageManager.readAll()

            for (i in 0 until DEFAULT_PAGE_COUNT) {
                val pageData = pageManager.readAt(i)
                assertEquals(PAGE_SIZE, pageData.size)
            }
        }

        @Test
        @DisplayName("페이지 데이터가 파일 내용과 일치")
        fun `page data matches file content`() {
            pageManager.readAll()

            // 첫 번째 페이지 검증
            val firstPage = pageManager.readAt(0)
            for (i in 0 until PAGE_SIZE) {
                assertEquals((i % 256).toByte(), firstPage[i])
            }

            // 두 번째 페이지 검증 (offset = PAGE_SIZE부터 시작)
            if (DEFAULT_PAGE_COUNT > 1) {
                val secondPage = pageManager.readAt(1)
                for (i in 0 until PAGE_SIZE) {
                    val expectedByte = ((PAGE_SIZE + i) % 256).toByte()
                    assertEquals(expectedByte, secondPage[i])
                }
            }
        }

        @Test
        @DisplayName("readAll() 재호출 시 페이지 목록 초기화")
        fun `readAll clears and reloads pages`() {
            pageManager.readAll()
            val initialCount = pageManager.pageCount()

            // 다시 호출해도 동일한 결과
            pageManager.readAll()

            assertEquals(initialCount, pageManager.pageCount())
        }

        @Test
        @DisplayName("빈 파일 읽기")
        fun `read empty file`() {
            // 빈 파일 생성
            val emptyFile = File.createTempFile("empty_test", ".dat")
            try {
                RandomAccessFile(emptyFile, "r").use { file ->
                    val emptyPageManager = PageManager(file)
                    emptyPageManager.readAll()

                    assertEquals(0, emptyPageManager.pageCount())
                    assertFalse(emptyPageManager.isLoaded())
                }
            } finally {
                emptyFile.delete()
            }
        }

        @Test
        @DisplayName("PAGE_SIZE보다 작은 파일 읽기 (부분 페이지)")
        fun `read file smaller than page size`() {
            val smallFile = File.createTempFile("small_test", ".dat")
            try {
                // PAGE_SIZE의 절반 크기 파일 생성
                RandomAccessFile(smallFile, "rw").use { file ->
                    for (i in 0 until PAGE_SIZE / 2) {
                        file.writeByte(i)
                    }
                }

                RandomAccessFile(smallFile, "r").use { file ->
                    val smallPageManager = PageManager(file)
                    smallPageManager.readAll()

                    assertEquals(1, smallPageManager.pageCount())
                    // 페이지 크기는 PAGE_SIZE (나머지는 0으로 패딩)
                    assertEquals(PAGE_SIZE, smallPageManager.readAt(0).size)
                }
            } finally {
                smallFile.delete()
            }
        }
    }

    @Nested
    @DisplayName("readAt() 테스트")
    inner class ReadAtTest {

        @Test
        @DisplayName("특정 페이지 ID로 데이터 조회")
        fun `read specific page by id`() {
            pageManager.readAll()

            val pageData = pageManager.readAt(0)

            assertNotNull(pageData)
            assertEquals(PAGE_SIZE, pageData.size)
        }

        @Test
        @DisplayName("첫 번째 페이지(id=0) 조회")
        fun `read first page`() {
            pageManager.readAll()

            val firstPage = pageManager.readAt(0)

            // 첫 바이트가 0인지 확인
            assertEquals(0.toByte(), firstPage[0])
        }

        @Test
        @DisplayName("마지막 페이지 조회")
        fun `read last page`() {
            pageManager.readAll()

            val lastPageId = DEFAULT_PAGE_COUNT - 1
            val lastPage = pageManager.readAt(lastPageId)

            assertNotNull(lastPage)
            assertEquals(PAGE_SIZE, lastPage.size)
        }

        @Test
        @DisplayName("중간 페이지 조회")
        fun `read middle page`() {
            pageManager.readAll()

            if (DEFAULT_PAGE_COUNT > 2) {
                val middlePageId = DEFAULT_PAGE_COUNT / 2
                val middlePage = pageManager.readAt(middlePageId)

                assertNotNull(middlePage)
                assertEquals(PAGE_SIZE, middlePage.size)

                // 첫 바이트 값 검증 (offset 기반)
                val expectedFirstByte = ((middlePageId * PAGE_SIZE) % 256).toByte()
                assertEquals(expectedFirstByte, middlePage[0])
            }
        }
    }

    @Nested
    @DisplayName("pageCount() 테스트")
    inner class PageCountTest {

        @Test
        @DisplayName("readAll() 전에는 0")
        fun `returns zero before readAll`() {
            assertEquals(0, pageManager.pageCount())
        }

        @Test
        @DisplayName("readAll() 후 올바른 페이지 수 반환")
        fun `returns correct count after readAll`() {
            pageManager.readAll()

            assertEquals(DEFAULT_PAGE_COUNT, pageManager.pageCount())
        }

        @Test
        @DisplayName("파일 크기에 따른 페이지 수 계산")
        fun `calculates page count based on file size`() {
            // PAGE_SIZE * 2.5 크기의 파일 -> 3페이지
            val customFile = File.createTempFile("custom_test", ".dat")
            try {
                val customSize = (PAGE_SIZE * 2.5).toInt()
                RandomAccessFile(customFile, "rw").use { file ->
                    for (i in 0 until customSize) {
                        file.writeByte(i % 256)
                    }
                }

                RandomAccessFile(customFile, "r").use { file ->
                    val customPageManager = PageManager(file)
                    customPageManager.readAll()

                    assertEquals(3, customPageManager.pageCount())
                }
            } finally {
                customFile.delete()
            }
        }
    }

    @Nested
    @DisplayName("isLoaded() 테스트")
    inner class IsLoadedTest {

        @Test
        @DisplayName("readAll() 전에는 false")
        fun `returns false before readAll`() {
            assertFalse(pageManager.isLoaded())
        }

        @Test
        @DisplayName("readAll() 후에는 true")
        fun `returns true after readAll`() {
            pageManager.readAll()

            assertTrue(pageManager.isLoaded())
        }

        @Test
        @DisplayName("빈 파일 로드 후에는 false")
        fun `returns false for empty file`() {
            val emptyFile = File.createTempFile("empty_test", ".dat")
            try {
                RandomAccessFile(emptyFile, "r").use { file ->
                    val emptyPageManager = PageManager(file)
                    emptyPageManager.readAll()

                    assertFalse(emptyPageManager.isLoaded())
                }
            } finally {
                emptyFile.delete()
            }
        }
    }

    @Nested
    @DisplayName("예외 처리 테스트")
    inner class ExceptionTest {

        @Test
        @DisplayName("readAll() 전에 readAt() 호출 시 IllegalArgumentException")
        fun `readAt before readAll throws IllegalArgumentException`() {
            val exception = assertThrows<IllegalArgumentException> {
                pageManager.readAt(0)
            }

            // 예외 메시지에 페이지 ID와 범위 정보 포함 확인
            assertTrue(exception.message?.contains("0") == true)
            assertTrue(exception.message?.contains("out of bounds") == true)
        }

        @Test
        @DisplayName("범위 외 페이지 ID 접근 시 예외 메시지 검증")
        fun `out of bounds access has descriptive message`() {
            pageManager.readAll()

            val invalidPageId = DEFAULT_PAGE_COUNT + 5
            val exception = assertThrows<IllegalArgumentException> {
                pageManager.readAt(invalidPageId)
            }

            // 예외 메시지에 요청한 페이지 ID 포함
            assertTrue(exception.message?.contains(invalidPageId.toString()) == true)
            // 유효한 범위 정보 포함
            assertTrue(exception.message?.contains("0..${DEFAULT_PAGE_COUNT - 1}") == true)
        }

        @Test
        @DisplayName("음수 페이지 ID 접근 시 예외")
        fun `negative page id throws exception`() {
            pageManager.readAll()

            val exception = assertThrows<IllegalArgumentException> {
                pageManager.readAt(-1)
            }

            assertTrue(exception.message?.contains("-1") == true)
        }

        @Test
        @DisplayName("Int.MAX_VALUE 페이지 ID 접근 시 예외")
        fun `max int page id throws exception`() {
            pageManager.readAll()

            assertThrows<IllegalArgumentException> {
                pageManager.readAt(Int.MAX_VALUE)
            }
        }

        @Test
        @DisplayName("정확히 경계값에서 예외 발생")
        fun `exception at exact boundary`() {
            pageManager.readAll()

            // 마지막 유효 인덱스는 성공
            assertDoesNotThrow {
                pageManager.readAt(DEFAULT_PAGE_COUNT - 1)
            }

            // 바로 다음 인덱스는 실패
            assertThrows<IllegalArgumentException> {
                pageManager.readAt(DEFAULT_PAGE_COUNT)
            }
        }
    }

    @Nested
    @DisplayName("경계값 테스트")
    inner class BoundaryTest {

        @Test
        @DisplayName("페이지 수 계산 검증")
        fun `verify page count calculation`() {
            pageManager.readAll()

            // 마지막 유효한 페이지 ID로 접근 가능
            assertDoesNotThrow {
                pageManager.readAt(DEFAULT_PAGE_COUNT - 1)
            }

            // 그 다음 ID는 접근 불가
            assertThrows<IllegalArgumentException> {
                pageManager.readAt(DEFAULT_PAGE_COUNT)
            }
        }

        @Test
        @DisplayName("단일 페이지 파일 경계 테스트")
        fun `single page file boundary`() {
            val singlePageFile = File.createTempFile("single_page_test", ".dat")
            try {
                RandomAccessFile(singlePageFile, "rw").use { file ->
                    for (i in 0 until PAGE_SIZE) {
                        file.writeByte(i % 256)
                    }
                }

                RandomAccessFile(singlePageFile, "r").use { file ->
                    val singlePageManager = PageManager(file)
                    singlePageManager.readAll()

                    assertEquals(1, singlePageManager.pageCount())

                    // 페이지 0은 접근 가능
                    assertDoesNotThrow { singlePageManager.readAt(0) }

                    // 페이지 1은 접근 불가
                    assertThrows<IllegalArgumentException> {
                        singlePageManager.readAt(1)
                    }
                }
            } finally {
                singlePageFile.delete()
            }
        }

        @Test
        @DisplayName("정확히 2페이지 크기 파일")
        fun `exact two pages file`() {
            val twoPageFile = File.createTempFile("two_page_test", ".dat")
            try {
                RandomAccessFile(twoPageFile, "rw").use { file ->
                    for (i in 0 until PAGE_SIZE * 2) {
                        file.writeByte(i % 256)
                    }
                }

                RandomAccessFile(twoPageFile, "r").use { file ->
                    val twoPageManager = PageManager(file)
                    twoPageManager.readAll()

                    assertEquals(2, twoPageManager.pageCount())
                    assertDoesNotThrow { twoPageManager.readAt(0) }
                    assertDoesNotThrow { twoPageManager.readAt(1) }
                    assertThrows<IllegalArgumentException> { twoPageManager.readAt(2) }
                }
            } finally {
                twoPageFile.delete()
            }
        }
    }

    @Nested
    @DisplayName("데이터 무결성 테스트")
    inner class DataIntegrityTest {

        @Test
        @DisplayName("연속 페이지의 데이터 연속성")
        fun `consecutive pages have continuous data`() {
            pageManager.readAll()

            for (pageId in 0 until DEFAULT_PAGE_COUNT - 1) {
                val currentPage = pageManager.readAt(pageId)
                val nextPage = pageManager.readAt(pageId + 1)

                // 현재 페이지 마지막 바이트 + 1 == 다음 페이지 첫 바이트
                val lastByteOfCurrent = currentPage[PAGE_SIZE - 1].toInt() and 0xFF
                val firstByteOfNext = nextPage[0].toInt() and 0xFF

                assertEquals(
                    (lastByteOfCurrent + 1) % 256,
                    firstByteOfNext,
                    "Data continuity failed between page $pageId and ${pageId + 1}"
                )
            }
        }

        @Test
        @DisplayName("동일 페이지 여러 번 읽기")
        fun `reading same page multiple times returns same data`() {
            pageManager.readAll()

            val firstRead = pageManager.readAt(0)
            val secondRead = pageManager.readAt(0)
            val thirdRead = pageManager.readAt(0)

            assertArrayEquals(firstRead, secondRead)
            assertArrayEquals(secondRead, thirdRead)
        }
    }

    @Nested
    @DisplayName("리소스 파일 기반 테스트")
    inner class ResourceFileTest {

        @Test
        @DisplayName("sample_pages.dat 파일 읽기 (6페이지)")
        fun `read sample pages file`() {
            val resourcePath = javaClass.getResource("$RESOURCE_PATH/sample_pages.dat")
            assertNotNull(resourcePath, "sample_pages.dat 리소스를 찾을 수 없습니다")

            val file = File(resourcePath!!.toURI())
            RandomAccessFile(file, "r").use { raf ->
                val pm = PageManager(raf)
                pm.readAll()

                // 6페이지가 로드되어야 함
                assertEquals(6, pm.pageCount())
                assertTrue(pm.isLoaded())

                // 첫 페이지 첫 바이트 = 0
                assertEquals(0.toByte(), pm.readAt(0)[0])

                // 마지막 페이지 마지막 바이트 = 95
                assertEquals(95.toByte(), pm.readAt(5)[PAGE_SIZE - 1])
            }
        }

        @Test
        @DisplayName("partial_page.dat 파일 읽기 (1.5페이지 -> 2페이지)")
        fun `read partial page file`() {
            val resourcePath = javaClass.getResource("$RESOURCE_PATH/partial_page.dat")
            assertNotNull(resourcePath, "partial_page.dat 리소스를 찾을 수 없습니다")

            val file = File(resourcePath!!.toURI())
            RandomAccessFile(file, "r").use { raf ->
                val pm = PageManager(raf)
                pm.readAll()

                // 24바이트 = 1.5페이지 -> 2페이지로 올림
                assertEquals(2, pm.pageCount())

                // 첫 페이지는 0-15 데이터
                val firstPage = pm.readAt(0)
                assertEquals(0.toByte(), firstPage[0])
                assertEquals(15.toByte(), firstPage[PAGE_SIZE - 1])

                // 두 번째 페이지는 16-23 데이터 + 나머지는 0으로 패딩
                val secondPage = pm.readAt(1)
                assertEquals(16.toByte(), secondPage[0])
                assertEquals(23.toByte(), secondPage[7])
            }
        }

        @Test
        @DisplayName("empty.dat 빈 파일 읽기")
        fun `read empty resource file`() {
            val resourcePath = javaClass.getResource("$RESOURCE_PATH/empty.dat")
            assertNotNull(resourcePath, "empty.dat 리소스를 찾을 수 없습니다")

            val file = File(resourcePath!!.toURI())
            RandomAccessFile(file, "r").use { raf ->
                val pm = PageManager(raf)
                pm.readAll()

                assertEquals(0, pm.pageCount())
                assertFalse(pm.isLoaded())
            }
        }

        @Test
        @DisplayName("리소스 파일 데이터 연속성 검증")
        fun `verify resource file data continuity`() {
            val resourcePath = javaClass.getResource("$RESOURCE_PATH/sample_pages.dat")
            assertNotNull(resourcePath)

            val file = File(resourcePath!!.toURI())
            RandomAccessFile(file, "r").use { raf ->
                val pm = PageManager(raf)
                pm.readAll()

                // 모든 바이트가 순차적으로 증가하는지 검증
                var expectedByte = 0
                for (pageId in 0 until pm.pageCount()) {
                    val pageData = pm.readAt(pageId)
                    for (i in 0 until PAGE_SIZE) {
                        assertEquals(
                            expectedByte.toByte(),
                            pageData[i],
                            "Mismatch at page $pageId, offset $i"
                        )
                        expectedByte++
                    }
                }
            }
        }
    }
}
