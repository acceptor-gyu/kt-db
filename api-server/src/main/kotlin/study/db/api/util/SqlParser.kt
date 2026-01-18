package study.db.api.util

import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest

/**
 * 간단한 SQL 파서
 *
 * 지원하는 쿼리:
 * - CREATE TABLE users (id INT, name VARCHAR)
 * - INSERT INTO users VALUES (id="1", name="John")
 * - SELECT * FROM users
 * - DROP TABLE users
 * - EXPLAIN SELECT * FROM users
 */
object SqlParser {

    /**
     * SQL 쿼리를 파싱하여 DbRequest로 변환
     */
    fun parse(sql: String): DbRequest {
        val trimmedSql = sql.trim().trimEnd(';')

        return when {
            trimmedSql.startsWith("CREATE TABLE", ignoreCase = true) -> parseCreateTable(trimmedSql)
            trimmedSql.startsWith("INSERT INTO", ignoreCase = true) -> parseInsert(trimmedSql)
            trimmedSql.startsWith("SELECT", ignoreCase = true) -> parseSelect(trimmedSql)
            trimmedSql.startsWith("DROP TABLE", ignoreCase = true) -> parseDropTable(trimmedSql)
            trimmedSql.startsWith("EXPLAIN", ignoreCase = true) -> parseExplain(trimmedSql)
            else -> throw IllegalArgumentException("Unsupported SQL query: $sql")
        }
    }

    /**
     * CREATE TABLE users (id INT, name VARCHAR)
     */
    private fun parseCreateTable(sql: String): DbRequest {
        // Regex: CREATE TABLE <tableName> (<columns>)
        val regex = Regex(
            """CREATE\s+TABLE\s+(\w+)\s*\(([^)]+)\)""",
            RegexOption.IGNORE_CASE
        )

        val match = regex.find(sql)
            ?: throw IllegalArgumentException("Invalid CREATE TABLE syntax: $sql")

        val tableName = match.groupValues[1]
        val columnsPart = match.groupValues[2]

        // Parse columns: "id INT, name VARCHAR"
        val columns = parseColumns(columnsPart)

        return DbRequest(
            command = DbCommand.CREATE_TABLE,
            tableName = tableName,
            columns = columns
        )
    }

    /**
     * Parse column definitions: "id INT, name VARCHAR, age INT"
     * Returns Map<columnName, columnType>
     */
    private fun parseColumns(columnsPart: String): Map<String, String> {
        val columns = mutableMapOf<String, String>()

        columnsPart.split(",").forEach { columnDef ->
            val parts = columnDef.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val columnName = parts[0]
                val columnType = parts[1].uppercase()
                columns[columnName] = columnType
            }
        }

        return columns
    }

    /**
     * INSERT INTO users VALUES (id="1", name="John")
     */
    private fun parseInsert(sql: String): DbRequest {
        // Regex: INSERT INTO <tableName> VALUES (<values>)
        val regex = Regex(
            """INSERT\s+INTO\s+(\w+)\s+VALUES\s*\(([^)]+)\)""",
            RegexOption.IGNORE_CASE
        )

        val match = regex.find(sql)
            ?: throw IllegalArgumentException("Invalid INSERT syntax: $sql")

        val tableName = match.groupValues[1]
        val valuesPart = match.groupValues[2]

        // Parse values: id="1", name="John"
        val values = parseValues(valuesPart)

        return DbRequest(
            command = DbCommand.INSERT,
            tableName = tableName,
            values = values
        )
    }

    /**
     * Parse values: id="1", name="John", age="30"
     * Returns Map<columnName, value>
     */
    private fun parseValues(valuesPart: String): Map<String, String> {
        val values = mutableMapOf<String, String>()

        // Pattern: columnName="value" or columnName='value' or columnName=value
        val regex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,)]+))""")

        regex.findAll(valuesPart).forEach { match ->
            val columnName = match.groupValues[1]
            val value = match.groupValues[2].ifEmpty {
                match.groupValues[3].ifEmpty {
                    match.groupValues[4]
                }
            }
            values[columnName] = value
        }

        return values
    }

    /**
     * SELECT * FROM users
     * SELECT id, name FROM users WHERE id="1"
     */
    private fun parseSelect(sql: String): DbRequest {
        // Regex: SELECT <columns> FROM <tableName> [WHERE <condition>]
        val regex = Regex(
            """SELECT\s+.+?\s+FROM\s+(\w+)(?:\s+WHERE\s+(.+))?""",
            RegexOption.IGNORE_CASE
        )

        val match = regex.find(sql)
            ?: throw IllegalArgumentException("Invalid SELECT syntax: $sql")

        val tableName = match.groupValues[1]
        val condition = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }

        return DbRequest(
            command = DbCommand.SELECT,
            tableName = tableName,
            condition = condition
        )
    }

    /**
     * DROP TABLE users
     */
    private fun parseDropTable(sql: String): DbRequest {
        // Regex: DROP TABLE <tableName>
        val regex = Regex(
            """DROP\s+TABLE\s+(\w+)""",
            RegexOption.IGNORE_CASE
        )

        val match = regex.find(sql)
            ?: throw IllegalArgumentException("Invalid DROP TABLE syntax: $sql")

        val tableName = match.groupValues[1]

        return DbRequest(
            command = DbCommand.DROP_TABLE,
            tableName = tableName
        )
    }

    /**
     * EXPLAIN SELECT * FROM users
     */
    private fun parseExplain(sql: String): DbRequest {
        // Regex: EXPLAIN <query>
        val regex = Regex(
            """EXPLAIN\s+(.+)""",
            RegexOption.IGNORE_CASE
        )

        val match = regex.find(sql)
            ?: throw IllegalArgumentException("Invalid EXPLAIN syntax: $sql")

        val innerQuery = match.groupValues[1]

        return DbRequest(
            command = DbCommand.EXPLAIN,
            sql = innerQuery
        )
    }
}
