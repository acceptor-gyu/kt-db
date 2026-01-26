package study.db.common

import kotlinx.serialization.Serializable

/**
 * Row - 테이블의 개별 행을 표현하는 데이터 클래스
 *
 * Tombstone 방식을 위한 deleted flag와 MVCC를 위한 version을 포함합니다.
 *
 * @property data 컬럼명 -> 값 매핑
 * @property deleted 삭제 여부 (true면 SELECT 시 필터링됨)
 * @property version 행의 버전 (UPDATE 시 증가)
 */
@Serializable
data class Row(
    val data: Map<String, String>,
    val deleted: Boolean = false,
    val version: Long = 0
) {
    /**
     * Row가 활성 상태인지 확인 (deleted가 아닌 경우)
     */
    fun isActive(): Boolean = !deleted

    /**
     * Row를 삭제 상태로 마킹
     */
    fun markAsDeleted(): Row = this.copy(deleted = true)

    /**
     * Row의 특정 필드 값을 업데이트하고 버전을 증가
     */
    fun update(updates: Map<String, String>): Row = Row(
        data = this.data + updates,
        deleted = false,
        version = this.version + 1
    )

    /**
     * 특정 컬럼 값 조회
     */
    fun getValue(columnName: String): String? = data[columnName]
}
