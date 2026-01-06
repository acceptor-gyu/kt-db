package study.db.storage_engine

/**
 * 디스크에서 읽어온 Page 단위 데이터
 * 실제 DB에서는 Page Header + Records + Trailer 등이 포함됨
 */
data class Page(
    val id: Int,
    val data: ByteArray
)
