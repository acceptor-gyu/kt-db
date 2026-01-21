package study.db.server.db_engine

import study.db.common.Table
import study.db.server.exception.ColumnNotFoundException
import study.db.server.exception.TypeMismatchException
import study.db.server.validation.TypeValidator

/**
 * Resolver - 이름 해석, 타입 결정, 의미 확정
 *
 * Parser에서 생성된 AST를 받아 의미적 검증을 수행:
 * 1. 이름 해석: 테이블, 컬럼, 별칭이 실제로 존재하는지 확인
 * 2. 타입 결정: 컬럼의 데이터 타입 확인 및 타입 호환성 검증
 * 3. 의미 확정: 중복 컬럼, 모호한 참조 등 의미적 오류 검출
 *
 * 에러 상황     | 에러 예시                    | 구현 상태
 * ------------|----------------------------|----------
 * 테이블 없음  | Table doesn't exist         | ✅ validateTableExists
 * 컬럼 없음   | Unknown column              | ✅ validateColumnExists
 * 타입 불일치 | Type mismatch               | ✅ validateColumnType
 * 별칭 오류   | Unknown table alias         | ⬜ TODO
 * 중복 컬럼   | Column ambiguous            | ⬜ TODO
 * DB 미선택   | No database selected        | ⬜ TODO
 */
object Resolver {

    /**
     * 테이블 존재 여부 확인
     *
     * @param tableName 확인할 테이블 이름
     * @param availableTables 사용 가능한 테이블 맵
     * @throws IllegalStateException 테이블이 존재하지 않을 때
     */
    fun validateTableExists(tableName: String, availableTables: Map<String, Table>) {
        if (!availableTables.containsKey(tableName)) {
            throw IllegalStateException("Table '$tableName' doesn't exist")
        }
    }

    /**
     * 컬럼 존재 여부 확인
     *
     * @param table 대상 테이블
     * @param columnName 확인할 컬럼 이름
     * @throws ColumnNotFoundException 컬럼이 테이블에 정의되지 않았을 때
     */
    fun validateColumnExists(table: Table, columnName: String) {
        if (!table.dataType.containsKey(columnName)) {
            throw ColumnNotFoundException(table.tableName, columnName)
        }
    }

    /**
     * 컬럼 타입 검증
     *
     * @param table 대상 테이블
     * @param columnName 컬럼 이름
     * @param value 삽입할 값
     * @throws ColumnNotFoundException 컬럼이 존재하지 않을 때
     * @throws TypeMismatchException 타입이 일치하지 않을 때
     */
    fun validateColumnType(table: Table, columnName: String, value: String) {
        val expectedType = table.dataType[columnName]
            ?: throw ColumnNotFoundException(table.tableName, columnName)

        TypeValidator.validate(value, expectedType)
    }

    /**
     * INSERT 데이터 일괄 검증
     *
     * @param table 대상 테이블
     * @param values 삽입할 데이터 (컬럼명 -> 값)
     * @throws ColumnNotFoundException 정의되지 않은 컬럼
     * @throws TypeMismatchException 타입 불일치
     */
    fun validateInsertData(table: Table, values: Map<String, String>) {
        values.forEach { (columnName, value) ->
            validateColumnType(table, columnName, value)
        }
    }
}