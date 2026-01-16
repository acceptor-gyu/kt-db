package study.db.server.elasticsearch.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories

@Configuration
@EnableElasticsearchRepositories(basePackages = ["study.db.server.elasticsearch.repository"])
@EnableConfigurationProperties(ElasticsearchIndexProperties::class)
class ElasticsearchConfig {

    @Bean
    fun elasticsearchIndexProperties(): ElasticsearchIndexProperties {
        return ElasticsearchIndexProperties()
    }
}

@ConfigurationProperties(prefix = "elasticsearch.index")
data class ElasticsearchIndexProperties(
    var queryLogs: String = "db-query-logs",
    var tableMetadata: String = "db-table-metadata",
    var indexMetadata: String = "db-index-metadata",
    var tableStatistics: String = "db-table-statistics",
    var queryPlans: String = "db-query-plans"
)
