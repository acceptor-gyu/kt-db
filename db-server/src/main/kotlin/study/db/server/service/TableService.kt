package study.db.server.service

import study.db.common.Table

class TableService {
    private val tables = mutableMapOf<String, Table>()

    fun createTable(tableName: String, columns: Map<String, String>): String {
        val table = Table(
            tableName = tableName,
            dataType = columns,
            value = emptyMap()
        )

        tables[tableName] = table
        return buildCreateTableQuery(table)
    }

    fun insert(tableName: String, values: Map<String, String>): Boolean {
        val table = tables[tableName] ?: return false

        val updatedTable = table.copy(value = table.value + values)
        tables[tableName] = updatedTable
        return true
    }

    fun select(tableName: String): Table? {
        return tables[tableName]
    }

    fun getAllTables(): List<Table> {
        return tables.values.toList()
    }

    fun dropTable(tableName: String): Boolean {
        return tables.remove(tableName) != null
    }

    fun tableExists(tableName: String): Boolean {
        return tables.containsKey(tableName)
    }

    private fun buildCreateTableQuery(table: Table): String {
        val columnsDefinition = table.dataType.entries.joinToString(", ") {
            "${it.key} ${it.value}"
        }
        return "CREATE TABLE ${table.tableName} ($columnsDefinition)"
    }
}
