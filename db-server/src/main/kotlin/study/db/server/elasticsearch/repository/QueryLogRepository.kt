package study.db.server.elasticsearch.repository

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository
import org.springframework.stereotype.Repository
import study.db.server.elasticsearch.document.QueryLog
import study.db.server.elasticsearch.document.QueryType

@Repository
interface QueryLogRepository : ElasticsearchRepository<QueryLog, String> {

    fun findByAffectedTablesContaining(tableName: String): List<QueryLog>

    fun findByQueryTypeAndAffectedTablesContaining(
        queryType: QueryType,
        tableName: String
    ): List<QueryLog>
}
