package study.db.server.storage

/**
 * PageId - 페이지 식별자
 *
 * 테이블 이름과 페이지 번호로 페이지를 고유하게 식별
 *
 * @property tableName 테이블 이름
 * @property pageNumber 페이지 번호 (0부터 시작)
 */
data class PageId(
    val tableName: String,
    val pageNumber: Int  // 0-based index
) {
    override fun toString(): String = "$tableName:$pageNumber"
}

/**
 * Page - 16KB 데이터 페이지
 *
 * MySQL InnoDB와 유사한 페이지 기반 저장 구조
 *
 * 페이지 구조: [Page Header 8B][Record 1][Record 2][Record 3]...
 *
 * Page Header (8 bytes):
 * - recordCount (4B): 이 페이지에 저장된 레코드 수
 * - freeSpaceOffset (4B): 다음 레코드를 삽입할 위치 (페이지 시작점 기준)
 */
data class Page(
    val pageId: PageId,
    val data: ByteArray,          // 16KB raw bytes (페이지 전체 데이터)
    val recordCount: Int = 0,
    val freeSpaceOffset: Int = HEADER_SIZE
) {
    companion object {
        /**
         * MySQL InnoDB의 기본 페이지 크기와 동일
         */
        const val PAGE_SIZE = 16 * 1024  // 16KB
        const val HEADER_SIZE = 8
    }

    /**
     * ByteArray는 structural equality를 지원하지 않으므로
     * contentEquals를 사용하여 비교
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Page
        if (pageId != other.pageId) return false
        if (recordCount != other.recordCount) return false
        if (freeSpaceOffset != other.freeSpaceOffset) return false
        if (!data.contentEquals(other.data)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pageId.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + recordCount
        result = 31 * result + freeSpaceOffset
        return result
    }
}
