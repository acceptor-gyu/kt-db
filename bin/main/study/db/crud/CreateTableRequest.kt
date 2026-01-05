package study.db.crud

data class CreateTableRequest (
    val tableName: String = "",
    val columns: Map<String, String>? = null,
    val values: Map<String, Any>? = null

    // create table query example
    // return "CREATE TABLE table_name (column1 datatype, column2 datatype, ...)"
    // return "INSERT INTO table_name (column1, column2) VALUES (value1, value2)"

)