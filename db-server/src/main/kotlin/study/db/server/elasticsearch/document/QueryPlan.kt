package study.db.server.elasticsearch.document

import org.springframework.data.annotation.Id
import org.springframework.data.elasticsearch.annotations.Document
import org.springframework.data.elasticsearch.annotations.Field
import org.springframework.data.elasticsearch.annotations.FieldType
import java.time.Instant

@Document(indexName = "db-query-plans")
data class QueryPlan(
    @Id
    @Field(name = "plan_id", type = FieldType.Keyword)
    val planId: String,

    @Field(name = "query_text", type = FieldType.Text)
    val queryText: String,

    @Field(name = "query_hash", type = FieldType.Keyword)
    val queryHash: String,

    @Field(name = "execution_steps", type = FieldType.Nested)
    val executionSteps: List<ExecutionStep>,

    @Field(name = "estimated_cost", type = FieldType.Double)
    val estimatedCost: Double,

    @Field(name = "estimated_rows", type = FieldType.Long)
    val estimatedRows: Long,

    @Field(name = "is_covered_query", type = FieldType.Boolean)
    val isCoveredQuery: Boolean = false,

    @Field(name = "generated_at", type = FieldType.Date)
    val generatedAt: Instant = Instant.now(),

    @Field(name = "metadata", type = FieldType.Flattened)
    val metadata: Map<String, Any> = emptyMap()
)

data class ExecutionStep(
    @Field(name = "step_id", type = FieldType.Integer)
    val stepId: Int,

    @Field(name = "step_type", type = FieldType.Keyword)
    val stepType: String,

    @Field(name = "table_name", type = FieldType.Keyword)
    val tableName: String?,

    @Field(name = "index_used", type = FieldType.Keyword)
    val indexUsed: String?,

    @Field(name = "filter_condition", type = FieldType.Text)
    val filterCondition: String?,

    @Field(name = "columns_accessed", type = FieldType.Keyword)
    val columnsAccessed: List<String> = emptyList(),

    @Field(name = "estimated_cost", type = FieldType.Double)
    val estimatedCost: Double,

    @Field(name = "estimated_rows", type = FieldType.Long)
    val estimatedRows: Long,

    @Field(name = "is_covered", type = FieldType.Boolean)
    val isCovered: Boolean = false,

    @Field(name = "description", type = FieldType.Text)
    val description: String,

    @Field(name = "extra", type = FieldType.Flattened)
    val extra: Map<String, Any> = emptyMap()
)

enum class ExplainStepType {
    TABLE_SCAN,
    INDEX_SCAN,
    INDEX_SEEK,
    COVERED_INDEX_SCAN,
    FILTER,
    SORT,
    LIMIT
}
