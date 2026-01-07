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
        fun `create page with id and data`() {
            val id = 1
            val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)

            val page = Page(id, data)

            assertEquals(id, page.id)
            assertArrayEquals(data, page.data)
        }

        @Test
        @DisplayName("빈 데이터로 생성")
        fun `create page with empty data`() {
            val page = Page(0, byteArrayOf())

            assertEquals(0, page.id)
            assertEquals(0, page.data.size)
        }

        @Test
        @DisplayName("큰 데이터로 생성")
        fun `create page with large data`() {
            val data = ByteArray(1024) { it.toByte() }

            val page = Page(99, data)

            assertEquals(99, page.id)
            assertEquals(1024, page.data.size)
        }
    }

    @Nested
    @DisplayName("데이터 접근 테스트")
    inner class DataAccessTest {

        @Test
        @DisplayName("id 필드 접근")
        fun `access id field`() {
            val page = Page(42, byteArrayOf(0x00))

            assertEquals(42, page.id)
        }

        @Test
        @DisplayName("data 필드 접근")
        fun `access data field`() {
            val expectedData = byteArrayOf(0x0A, 0x0B, 0x0C)
            val page = Page(1, expectedData)

            assertArrayEquals(expectedData, page.data)
        }
    }

    @Nested
    @DisplayName("동등성 테스트")
    inner class EqualityTest {

        @Test
        @DisplayName("같은 id와 data를 가진 Page는 동등")
        fun `pages with same id and data are equal`() {
            val data = byteArrayOf(0x01, 0x02)
            val page1 = Page(1, data)
            val page2 = Page(1, data)

            assertEquals(page1, page2)
        }

        @Test
        @DisplayName("다른 id를 가진 Page는 동등하지 않음")
        fun `pages with different id are not equal`() {
            val data = byteArrayOf(0x01, 0x02)
            val page1 = Page(1, data)
            val page2 = Page(2, data)

            assertNotEquals(page1, page2)
        }

        @Test
        @DisplayName("copy로 생성한 Page")
        fun `copy creates new page with same values`() {
            val original = Page(1, byteArrayOf(0x01))
            val copied = original.copy()

            assertEquals(original.id, copied.id)
            assertArrayEquals(original.data, copied.data)
        }

        @Test
        @DisplayName("copy로 id만 변경")
        fun `copy with different id`() {
            val original = Page(1, byteArrayOf(0x01))
            val copied = original.copy(id = 2)

            assertEquals(2, copied.id)
            assertArrayEquals(original.data, copied.data)
        }
    }

    @Nested
    @DisplayName("toString 테스트")
    inner class ToStringTest {

        @Test
        @DisplayName("toString 포함 id")
        fun `toString contains id`() {
            val page = Page(42, byteArrayOf(0x01))

            assertTrue(page.toString().contains("42"))
        }
    }
}
