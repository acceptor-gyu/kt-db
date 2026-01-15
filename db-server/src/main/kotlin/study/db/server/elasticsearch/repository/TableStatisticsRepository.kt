package study.db.server.elasticsearch.repository

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository
import org.springframework.stereotype.Repository
import study.db.server.elasticsearch.document.TableStatistics

@Repository
interface TableStatisticsRepository : ElasticsearchRepository<TableStatistics, String> {

    fun findByTableName(tableName: String): TableStatistics?

    fun findAllByOrderByUpdatedAtDesc(): List<TableStatistics>
}
