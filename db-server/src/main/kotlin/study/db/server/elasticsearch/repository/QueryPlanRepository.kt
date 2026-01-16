package study.db.server.elasticsearch.repository

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository
import org.springframework.stereotype.Repository
import study.db.server.elasticsearch.document.QueryPlan

@Repository
interface QueryPlanRepository : ElasticsearchRepository<QueryPlan, String> {

    fun findByQueryHash(queryHash: String): QueryPlan?
}
