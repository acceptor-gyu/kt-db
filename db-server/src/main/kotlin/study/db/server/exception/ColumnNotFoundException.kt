package study.db.server.exception

/**
 * 테이블에 정의되지 않은 컬럼을 사용할 때 발생하는 예외
 *
 * @param tableName 테이블 이름
 * @param columnName 존재하지 않는 컬럼 이름
 */
class ColumnNotFoundException(
    val tableName: String,
    val columnName: String
) : IllegalArgumentException(
    "Column '$columnName' does not exist in table '$tableName'"
)
