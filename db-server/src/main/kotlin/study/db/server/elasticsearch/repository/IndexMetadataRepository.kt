package study.db.server.elasticsearch.repository

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository
import org.springframework.stereotype.Repository
import study.db.server.elasticsearch.document.IndexMetadata
import study.db.server.elasticsearch.document.IndexStatus

@Repository
interface IndexMetadataRepository : ElasticsearchRepository<IndexMetadata, String> {

    fun findByIndexName(indexName: String): IndexMetadata?

    fun findByTableName(tableName: String): List<IndexMetadata>

    fun findByTableNameAndStatus(tableName: String, status: IndexStatus): List<IndexMetadata>

    fun findByIndexNameAndStatus(indexName: String, status: IndexStatus): IndexMetadata?

    fun findByTableNameAndIndexColumnsContaining(tableName: String, columnName: String): List<IndexMetadata>

    fun findByStatus(status: IndexStatus): List<IndexMetadata>
}
