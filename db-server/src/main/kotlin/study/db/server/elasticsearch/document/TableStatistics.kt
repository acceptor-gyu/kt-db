package study.db.server.elasticsearch.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "db-table-statistics")
data class TableStatistics(
    @Id
    @Field(name = "stats_id", type = FieldType.Keyword)
    val statsId: String,

    @Field(name = "table_name", type = FieldType.Keyword)
    val tableName: String,

    @Field(name = "total_rows", type = FieldType.Long)
    val totalRows: Long,

    @Field(name = "column_stats", type = FieldType.Nested)
    val columnStats: List<ColumnStatistics>,

    @Field(name = "updated_at", type = FieldType.Date)
    val updatedAt: Instant = Instant.now()
)

data class ColumnStatistics(
    @Field(name = "column_name", type = FieldType.Keyword)
    val columnName: String,

    @Field(name = "distinct_count", type = FieldType.Long)
    val distinctCount: Long,

    @Field(name = "null_count", type = FieldType.Long)
    val nullCount: Long = 0,

    @Field(name = "min_value", type = FieldType.Text)
    val minValue: String? = null,

    @Field(name = "max_value", type = FieldType.Text)
    val maxValue: String? = null,

    @Field(name = "avg_length", type = FieldType.Double)
    val avgLength: Double? = null
) {
    /**
     * Selectivity 계산
     *
     * Selectivity = 1 / distinctCount
     * - distinctCount가 높을수록 selectivity가 낮음 (좋음)
     * - distinctCount가 낮을수록 selectivity가 높음 (나쁨)
     *
     * 예:
     * - email 컬럼: distinctCount = 1000 → selectivity = 0.001 (0.1%)
     * - gender 컬럼: distinctCount = 2 → selectivity = 0.5 (50%)
     */
    fun calculateSelectivity(): Double {
        return if (distinctCount > 0) {
            1.0 / distinctCount
        } else {
            1.0  // 데이터 없으면 최악의 경우로 가정
        }
    }

    /**
     * 카디널리티 (Cardinality)
     * - 고유한 값의 개수
     * - EXPLAIN에서 인덱스 효율성 판단에 사용
     */
    fun getCardinality(): Long = distinctCount
}
