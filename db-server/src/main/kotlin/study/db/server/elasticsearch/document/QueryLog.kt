package study.db.server.elasticsearch.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "#{@elasticsearchIndexProperties.queryLogs}")
data class QueryLog(
    @Id
    @Field(name = "query_id", type = FieldType.Keyword)
    val queryId: String,

    @Field(name = "query_type", type = FieldType.Keyword)
    val queryType: QueryType,

    @Field(name = "query_text", type = FieldType.Text)
    val queryText: String,

    @Field(name = "connection_id", type = FieldType.Keyword)
    val connectionId: String,

    @Field(name = "user", type = FieldType.Keyword)
    val user: String,

    @Field(name = "timestamp", type = FieldType.Date)
    val timestamp: Instant = Instant.now(),

    @Field(name = "execution_time_ms", type = FieldType.Long)
    val executionTimeMs: Long? = null,

    @Field(name = "status", type = FieldType.Keyword)
    val status: QueryStatus,

    @Field(name = "error_message", type = FieldType.Text)
    val errorMessage: String? = null,

    @Field(name = "affected_tables", type = FieldType.Keyword)
    val affectedTables: List<String> = emptyList(),

    @Field(name = "rows_affected", type = FieldType.Integer)
    val rowsAffected: Int? = null,

    @Field(name = "metadata", type = FieldType.Flattened)
    val metadata: Map<String, Any> = emptyMap()
)

enum class QueryType {
    DDL,
    DML,
    DQL,
    UNKNOWN
}

enum class QueryStatus {
    SUCCESS,
    FAILED,
    IN_PROGRESS
}
