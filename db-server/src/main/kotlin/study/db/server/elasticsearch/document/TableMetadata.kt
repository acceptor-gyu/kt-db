package study.db.server.elasticsearch.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "db-table-metadata")
data class TableMetadata(
    @Id
    @Field(name = "table_id", type = FieldType.Keyword)
    val tableId: String,

    @Field(name = "table_name", type = FieldType.Keyword)
    val tableName: String,

    @Field(name = "columns", type = FieldType.Nested)
    val columns: List<ColumnInfo>,

    @Field(name = "created_at", type = FieldType.Date)
    val createdAt: Instant = Instant.now(),

    @Field(name = "updated_at", type = FieldType.Date)
    val updatedAt: Instant = Instant.now(),

    @Field(name = "status", type = FieldType.Keyword)
    val status: TableStatus = TableStatus.ACTIVE,

    @Field(name = "estimated_row_count", type = FieldType.Long)
    val estimatedRowCount: Long = 0
)

data class ColumnInfo(
    @Field(name = "name", type = FieldType.Keyword)
    val name: String,

    @Field(name = "data_type", type = FieldType.Keyword)
    val dataType: String,

    @Field(name = "nullable", type = FieldType.Boolean)
    val nullable: Boolean = true,

    @Field(name = "primary_key", type = FieldType.Boolean)
    val primaryKey: Boolean = false,

    @Field(name = "default_value", type = FieldType.Text)
    val defaultValue: String? = null
)

enum class TableStatus {
    ACTIVE,
    DROPPED
}
