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
            name = request.name,
            columns = request.columns
        )
        
        tables[request.name] = table
        
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
        val columnsDefinition = table.columns.joinToString(", ") { 
            "${it.name} ${it.dataType}" 
        }
        return "CREATE TABLE ${table.name} ($columnsDefinition)"
    }
}
