package study.db.server.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Page 테스트")
class PageTest {

    @Nested
    @DisplayName("생성 테스트")
    inner class CreationTest {

        @Test
        @DisplayName("기본 생성")
        fun `create page with pageId and data`() {
            val pageId = PageId("users", 1)
            val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)

            val page = Page(pageId, data)

            assertEquals(pageId, page.pageId)
            assertArrayEquals(data, page.data)
        }

        @Test
        @DisplayName("빈 데이터로 생성")
        fun `create page with empty data`() {
            val page = Page(PageId("test", 0), byteArrayOf())

            assertEquals("test", page.pageId.tableName)
            assertEquals(0, page.pageId.pageNumber)
            assertEquals(0, page.data.size)
        }

        @Test
        @DisplayName("큰 데이터로 생성")
        fun `create page with large data`() {
            val data = ByteArray(1024) { it.toByte() }

            val page = Page(PageId("large_table", 99), data)

            assertEquals(99, page.pageId.pageNumber)
            assertEquals(1024, page.data.size)
        }
    }

    @Nested
    @DisplayName("데이터 접근 테스트")
    inner class DataAccessTest {

        @Test
        @DisplayName("pageId 필드 접근")
        fun `access pageId field`() {
            val page = Page(PageId("test_table", 42), byteArrayOf(0x00))

            assertEquals("test_table", page.pageId.tableName)
            assertEquals(42, page.pageId.pageNumber)
        }

        @Test
        @DisplayName("data 필드 접근")
        fun `access data field`() {
            val expectedData = byteArrayOf(0x0A, 0x0B, 0x0C)
            val page = Page(PageId("test", 1), expectedData)

            assertArrayEquals(expectedData, page.data)
        }
    }

    @Nested
    @DisplayName("동등성 테스트")
    inner class EqualityTest {

        @Test
        @DisplayName("같은 pageId와 data를 가진 Page는 동등")
        fun `pages with same pageId and data are equal`() {
            val data = byteArrayOf(0x01, 0x02)
            val pageId = PageId("users", 1)
            val page1 = Page(pageId, data)
            val page2 = Page(pageId, data)

            assertEquals(page1, page2)
        }

        @Test
        @DisplayName("다른 pageId를 가진 Page는 동등하지 않음")
        fun `pages with different pageId are not equal`() {
            val data = byteArrayOf(0x01, 0x02)
            val page1 = Page(PageId("users", 1), data)
            val page2 = Page(PageId("users", 2), data)

            assertNotEquals(page1, page2)
        }

        @Test
        @DisplayName("copy로 생성한 Page")
        fun `copy creates new page with same values`() {
            val original = Page(PageId("test", 1), byteArrayOf(0x01))
            val copied = original.copy()

            assertEquals(original.pageId, copied.pageId)
            assertArrayEquals(original.data, copied.data)
        }

        @Test
        @DisplayName("copy로 pageId만 변경")
        fun `copy with different pageId`() {
            val original = Page(PageId("test", 1), byteArrayOf(0x01))
            val copied = original.copy(pageId = PageId("test", 2))

            assertEquals(2, copied.pageId.pageNumber)
            assertArrayEquals(original.data, copied.data)
        }
    }

    @Nested
    @DisplayName("toString 테스트")
    inner class ToStringTest {

        @Test
        @DisplayName("toString 포함 pageId")
        fun `toString contains pageId`() {
            val page = Page(PageId("users", 42), byteArrayOf(0x01))

            val str = page.toString()
            assertTrue(str.contains("users") || str.contains("42"))
        }
    }
}
