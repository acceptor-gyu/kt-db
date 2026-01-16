package study.db.server.elasticsearch.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "#{@elasticsearchIndexProperties.indexMetadata}")
data class IndexMetadata(
    @Id
    @Field(name = "index_id", type = FieldType.Keyword)
    val indexId: String,

    @Field(name = "index_name", type = FieldType.Keyword)
    val indexName: String,

    @Field(name = "table_name", type = FieldType.Keyword)
    val tableName: String,

    @Field(name = "index_columns", type = FieldType.Keyword)
    val indexColumns: List<String>,

    @Field(name = "index_type", type = FieldType.Keyword)
    val indexType: IndexType = IndexType.BTREE,

    @Field(name = "unique", type = FieldType.Boolean)
    val unique: Boolean = false,

    @Field(name = "created_at", type = FieldType.Date)
    val createdAt: Instant = Instant.now(),

    @Field(name = "status", type = FieldType.Keyword)
    val status: IndexStatus = IndexStatus.ACTIVE
)

enum class IndexType {
    BTREE,
    HASH,
    FULLTEXT
}

enum class IndexStatus {
    ACTIVE,
    DROPPED
}
