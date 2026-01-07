package study.db.server.storage

/**
 * 디스크에서 읽어온 Page 단위 데이터
 * 실제 DB에서는 Page Header + Records + Trailer 등이 포함됨
 */
data class Page(
    val id: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Page) return false

        return id == other.id && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + data.contentHashCode()
        return result
    }
}
