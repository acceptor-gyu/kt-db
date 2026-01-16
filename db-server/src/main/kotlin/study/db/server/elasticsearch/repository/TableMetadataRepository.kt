package study.db.server.elasticsearch.repository

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository
import org.springframework.stereotype.Repository
import study.db.server.elasticsearch.document.TableMetadata
import study.db.server.elasticsearch.document.TableStatus

@Repository
interface TableMetadataRepository : ElasticsearchRepository<TableMetadata, String> {

    fun findByTableName(tableName: String): TableMetadata?

    fun findByStatus(status: TableStatus): List<TableMetadata>

    fun findByTableNameAndStatus(tableName: String, status: TableStatus): TableMetadata?
}
