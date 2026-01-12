package study.db.server.service

import study.db.common.Table
import java.util.concurrent.ConcurrentHashMap

/**
 * TableService - Thread-safe 테이블 관리 서비스
 *
 * 여러 ConnectionHandler가 동시에 접근하므로 Thread-safe하게 구현됨:
 * - ConcurrentHashMap 사용으로 기본 thread-safety 보장
 * - insert, createTable 등의 복합 연산은 atomic operation으로 처리
 */
class TableService {
    /**
     * 테이블 저장소 (Thread-safe)
     * - ConcurrentHashMap: 동시 읽기/쓰기 안전
     * - Key: 테이블 이름
     * - Value: Table 객체 (immutable copy 사용)
     */
    private val tables = ConcurrentHashMap<String, Table>()

    /**
     * 테이블 생성 (Thread-safe)
     *
     * @param tableName 테이블 이름
     * @param columns 컬럼 정의 (컬럼명 -> 데이터 타입)
     * @return CREATE TABLE SQL 쿼리 문자열
     */
    fun createTable(tableName: String, columns: Map<String, String>): String {
        val table = Table(
            tableName = tableName,
            dataType = columns,
            value = emptyMap()
        )

        // putIfAbsent: 이미 존재하면 기존 테이블 유지 (atomic operation)
        // 동시에 같은 이름의 테이블을 생성하려는 경우 방지
        tables.putIfAbsent(tableName, table)

        return buildCreateTableQuery(table)
    }

    /**
     * 데이터 삽입 (Thread-safe)
     *
     * compute를 사용하여 read-modify-write를 atomic하게 처리:
     * 1. 테이블 존재 여부 확인
     * 2. 기존 값과 새 값 병합
     * 3. 업데이트된 테이블 저장
     * 위 3단계가 하나의 atomic operation으로 실행됨
     *
     * @param tableName 테이블 이름
     * @param values 삽입할 데이터
     * @return 성공 여부
     */
    fun insert(tableName: String, values: Map<String, String>): Boolean {
        // compute: key에 대한 값을 atomic하게 계산/업데이트
        // 여러 스레드가 동시에 같은 테이블에 insert해도 안전
        val result = tables.compute(tableName) { _, existingTable ->
            // 테이블이 없으면 null 반환 (변경 없음)
            existingTable?.copy(value = existingTable.value + values)
        }

        // compute 결과가 null이 아니면 성공 (테이블이 존재했음)
        return result != null
    }

    /**
     * 테이블 조회 (Thread-safe)
     *
     * @param tableName 테이블 이름
     * @return Table 객체 또는 null
     */
    fun select(tableName: String): Table? {
        return tables[tableName]
    }

    /**
     * 모든 테이블 조회 (Thread-safe)
     *
     * @return 모든 테이블 리스트 (스냅샷)
     */
    fun getAllTables(): List<Table> {
        return tables.values.toList()
    }

    /**
     * 테이블 삭제 (Thread-safe)
     *
     * @param tableName 테이블 이름
     * @return 성공 여부 (테이블이 존재했으면 true)
     */
    fun dropTable(tableName: String): Boolean {
        return tables.remove(tableName) != null
    }

    /**
     * 테이블 존재 여부 확인 (Thread-safe)
     *
     * @param tableName 테이블 이름
     * @return 존재 여부
     */
    fun tableExists(tableName: String): Boolean {
        return tables.containsKey(tableName)
    }

    /**
     * CREATE TABLE SQL 쿼리 문자열 생성
     *
     * @param table 테이블 객체
     * @return SQL 쿼리 문자열
     */
    private fun buildCreateTableQuery(table: Table): String {
        val columnsDefinition = table.dataType.entries.joinToString(", ") {
            "${it.key} ${it.value}"
        }
        return "CREATE TABLE ${table.tableName} ($columnsDefinition)"
    }
}
