package study.db.crud.service

import org.springframework.stereotype.Service
import study.db.crud.CreateTableRequest
import study.db.crud.Table

@Service
class TableService {

    private val tables = mutableMapOf<String, Table>()

    fun createTable(request: CreateTableRequest): String {
        // 테이블 생성 로직
        val table = Table(
            tableName = request.tableName,
            dataType = request.columns ?: emptyMap(),
            value = request.values ?: emptyMap()
        )

        tables[request.tableName] = table

        return buildCreateTableQuery(table)
    }

    fun getTable(tableName: String): Table? {
        return tables[tableName]
    }

    fun getAllTables(): List<Table> {
        return tables.values.toList()
    }

    fun deleteTable(tableName: String): Boolean {
        return tables.remove(tableName) != null
    }

    private fun buildCreateTableQuery(table: Table): String {
        val columnsDefinition = table.dataType.entries.joinToString(", ") {
            "${it.key} ${it.value}"
        }
        return "CREATE TABLE ${table.tableName} ($columnsDefinition)"
    }
}
