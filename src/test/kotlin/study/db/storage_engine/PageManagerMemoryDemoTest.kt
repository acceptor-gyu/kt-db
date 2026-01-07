package study.db.storage_engine

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * PageManager가 파일을 메모리로 로드하는 과정을 시각적으로 보여주는 데모 테스트
 */
@DisplayName("PageManager 메모리 로딩 데모")
class PageManagerMemoryDemoTest {

    companion object {
        private const val RESOURCE_PATH = "/storage_engine"
    }

    @Test
    @DisplayName("sample_pages.dat 메모리 로딩 시각화")
    fun `visualize sample_pages loading`() {
        val resourcePath = javaClass.getResource("$RESOURCE_PATH/sample_pages.dat")!!
        val file = File(resourcePath.toURI())

        println("=" .repeat(70))
        println("📁 파일: sample_pages.dat (${file.length()} bytes)")
        println("=" .repeat(70))

        RandomAccessFile(file, "r").use { raf ->
            val pm = PageManager(raf)

            println("\n🔄 readAll() 실행 전:")
            println("   - pageCount(): ${pm.pageCount()}")
            println("   - isLoaded(): ${pm.isLoaded()}")

            pm.readAll()

            println("\n✅ readAll() 실행 후:")
            println("   - pageCount(): ${pm.pageCount()}")
            println("   - isLoaded(): ${pm.isLoaded()}")

            println("\n" + "=" .repeat(70))
            println("📦 메모리에 로드된 Page 구조")
            println("=" .repeat(70))

            for (pageId in 0 until pm.pageCount()) {
                val pageData = pm.readAt(pageId)
                val offset = pageId * PAGE_SIZE

                println("\n┌─ Page $pageId (offset: $offset-${offset + PAGE_SIZE - 1})")
                println("│  Size: ${pageData.size} bytes")
                println("│")
                println("│  Hex:  ${pageData.joinToString(" ") { "%02X".format(it) }}")
                println("│")
                println("│  Dec:  ${pageData.joinToString(" ") { "%3d".format(it.toInt() and 0xFF) }}")
                println("│")

                // ASCII 표현 (출력 가능한 문자만)
                val ascii = pageData.map { b ->
                    val c = (b.toInt() and 0xFF).toChar()
                    if (c.isLetterOrDigit() || c in "!@#\$%^&*()_+-=[]{}|;':\",./<>?") c else '.'
                }.joinToString("")
                println("│  Char: $ascii")
                println("└" + "─".repeat(50))
            }
        }
    }

    @Test
    @DisplayName("partial_page.dat 메모리 로딩 시각화 (부분 페이지)")
    fun `visualize partial_page loading`() {
        val resourcePath = javaClass.getResource("$RESOURCE_PATH/partial_page.dat")!!
        val file = File(resourcePath.toURI())

        println("=" .repeat(70))
        println("📁 파일: partial_page.dat (${file.length()} bytes)")
        println("   ⚠️ 24 bytes = 1.5 pages → 올림하여 2 pages로 처리")
        println("=" .repeat(70))

        RandomAccessFile(file, "r").use { raf ->
            val pm = PageManager(raf)
            pm.readAll()

            println("\n📊 로드 결과:")
            println("   - pageCount(): ${pm.pageCount()}")
            println("   - 파일 크기: ${file.length()} bytes")
            println("   - PAGE_SIZE: $PAGE_SIZE bytes")
            println("   - 계산: ceil(24 / 16) = 2 pages")

            for (pageId in 0 until pm.pageCount()) {
                val pageData = pm.readAt(pageId)
                val offset = pageId * PAGE_SIZE
                val actualDataEnd = minOf((pageId + 1) * PAGE_SIZE, file.length().toInt())
                val actualDataInPage = actualDataEnd - offset

                println("\n┌─ Page $pageId")
                println("│  실제 데이터: ${actualDataInPage} bytes, 패딩: ${PAGE_SIZE - actualDataInPage} bytes")
                println("│")
                println("│  Hex: ${pageData.joinToString(" ") { "%02X".format(it) }}")

                if (actualDataInPage < PAGE_SIZE) {
                    println("│       ${"   ".repeat(actualDataInPage)}${"^^".repeat(PAGE_SIZE - actualDataInPage).replace("^^", " 00")} ← 패딩")
                }
                println("└" + "─".repeat(50))
            }
        }
    }

    @Test
    @DisplayName("Page 객체 구조 시각화")
    fun `visualize Page object structure`() {
        val resourcePath = javaClass.getResource("$RESOURCE_PATH/sample_pages.dat")!!
        val file = File(resourcePath.toURI())

        println("=" .repeat(70))
        println("🔍 Page 객체 내부 구조")
        println("=" .repeat(70))

        RandomAccessFile(file, "r").use { raf ->
            val pm = PageManager(raf)
            pm.readAll()

            // 첫 번째 페이지로 예시
            val pageData = pm.readAt(0)

            println("""
                |
                | Page 객체 (data class):
                | ┌────────────────────────────────────────┐
                | │  id: Int = 0                          │
                | │  data: ByteArray                      │
                | │    ┌─────────────────────────────┐    │
                | │    │ [0]  = 0x00 (0)             │    │
                | │    │ [1]  = 0x01 (1)             │    │
                | │    │ [2]  = 0x02 (2)             │    │
                | │    │ ...                         │    │
                | │    │ [15] = 0x0F (15)            │    │
                | │    └─────────────────────────────┘    │
                | │    size: $PAGE_SIZE bytes                      │
                | └────────────────────────────────────────┘
                |
                | 메모리 레이아웃:
                |
                | pages: MutableList<Page>
                |   ├── [0] Page(id=0, data=[00,01,02...0F])
                |   ├── [1] Page(id=1, data=[10,11,12...1F])
                |   ├── [2] Page(id=2, data=[20,21,22...2F])
                |   ├── [3] Page(id=3, data=[30,31,32...3F])
                |   ├── [4] Page(id=4, data=[40,41,42...4F])
                |   └── [5] Page(id=5, data=[50,51,52...5F])
                |
            """.trimMargin())

            println("실제 데이터 검증:")
            for (pageId in 0 until pm.pageCount()) {
                val data = pm.readAt(pageId)
                val firstByte = data[0].toInt() and 0xFF
                val lastByte = data[PAGE_SIZE - 1].toInt() and 0xFF
                println("  Page $pageId: first=${"%02X".format(firstByte)}, last=${"%02X".format(lastByte)}")
            }
        }
    }
}
