package study.db.server.service

import org.slf4j.LoggerFactory
import study.db.common.Table
import study.db.server.db_engine.Resolver
import study.db.server.storage.TableFileManager
import java.util.concurrent.ConcurrentHashMap

/**
 * TableService - Thread-safe 테이블 관리 서비스
 *
 * 여러 ConnectionHandler가 동시에 접근하므로 Thread-safe하게 구현됨:
 * - ConcurrentHashMap 사용으로 기본 thread-safety 보장
 * - insert, createTable 등의 복합 연산은 atomic operation으로 처리
 *
 * @param tableFileManager 파일 기반 persistence 관리자 (optional)
 */
class TableService(
    private val tableFileManager: TableFileManager? = null
) {
    private val logger = LoggerFactory.getLogger(TableService::class.java)

    /**
     * 테이블 저장소 (Thread-safe)
     * - ConcurrentHashMap: 동시 읽기/쓰기 안전
     * - Key: 테이블 이름
     * - Value: Table 객체 (immutable copy 사용)
     */
    private val tables = ConcurrentHashMap<String, Table>()

    init {
        // 파일에서 테이블 로드
        tableFileManager?.let { manager ->
            manager.listAllTables().forEach { tableName ->
                try {
                    manager.readTable(tableName)?.let { table ->
                        tables[table.tableName] = table
                        logger.info("Loaded table '${table.tableName}' with ${table.rows.size} rows from disk")
                    }
                } catch (e: Exception) {
                    logger.error("Failed to load table '$tableName' from disk", e)
                }
            }
            logger.info("Total ${tables.size} tables loaded from disk")
        }
    }

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
            rows = emptyList()
        )

        // putIfAbsent: 이미 존재하면 기존 테이블 유지 (atomic operation)
        // 동시에 같은 이름의 테이블을 생성하려는 경우 방지
        tables.putIfAbsent(tableName, table)

        // 파일로 저장
        tableFileManager?.writeTable(table)

        return buildCreateTableQuery(table)
    }

    /**
     * 데이터 삽입 (Thread-safe with validation)
     *
     * 검증 단계:
     * 1. 테이블 존재 여부 확인
     * 2. 컬럼 존재 여부 확인 (정의되지 않은 컬럼 거부)
     * 3. 타입 검증 (엄격한 타입 매칭)
     *
     * compute를 사용하여 read-modify-write를 atomic하게 처리:
     * 1. 테이블 존재 여부 확인
     * 2. 기존 값과 새 값 병합
     * 3. 업데이트된 테이블 저장
     * 위 3단계가 하나의 atomic operation으로 실행됨
     *
     * @param tableName 테이블 이름
     * @param values 삽입할 데이터
     * @throws IllegalStateException 테이블이 존재하지 않을 때
     * @throws ColumnNotFoundException 컬럼이 테이블에 정의되지 않았을 때
     * @throws TypeMismatchException 타입이 일치하지 않을 때
     * @throws UnsupportedTypeException 지원하지 않는 타입일 때
     */
    fun insert(tableName: String, values: Map<String, String>) {
        // 1. 테이블 존재 여부 확인
        val existingTable = tables[tableName]
            ?: throw IllegalStateException("Table '$tableName' not found")

        // 2. 컬럼 존재 여부 및 타입 검증
        validateInsertData(existingTable, values)

        // 3. 데이터 삽입 (atomic operation)
        // compute: key에 대한 값을 atomic하게 계산/업데이트
        // 여러 스레드가 동시에 같은 테이블에 insert해도 안전
        val updated = tables.compute(tableName) { _, table ->
            table?.copy(rows = table.rows + values)
        }

        // 파일로 저장
        updated?.let { tableFileManager?.writeTable(it) }
    }

    /**
     * INSERT 데이터 검증
     *
     * Resolver를 통해 컬럼 존재 여부 및 타입 검증 수행
     *
     * @param table 대상 테이블
     * @param values 삽입할 데이터
     * @throws ColumnNotFoundException 정의되지 않은 컬럼
     * @throws TypeMismatchException 타입 불일치
     */
    private fun validateInsertData(table: Table, values: Map<String, String>) {
        Resolver.validateInsertData(table, values)
    }

    /**
     * 데이터 삭제 (Thread-safe with tombstone marking)
     *
     * WHERE 조건에 맞는 행을 삭제합니다.
     * - 파일 매니저가 있으면: 논리적 삭제 (deleted=true, tombstone 방식)
     * - 파일 매니저가 없으면: 물리적 삭제 (메모리에서 행 제거)
     *
     * @param tableName 테이블 이름
     * @param whereClause WHERE 조건 (null이면 전체 삭제)
     * @return 삭제된 행 개수
     * @throws IllegalStateException 테이블이 존재하지 않을 때
     */
    fun delete(tableName: String, whereClause: String?): Int {
        // 1. 테이블 존재 여부 확인
        val existingTable = tables[tableName]
            ?: throw IllegalStateException("Table '$tableName' not found")

        // 2. WHERE 조건 파싱 (간단한 key=value 형식)
        val (columnName, value) = if (whereClause != null) {
            parseSimpleWhereCondition(whereClause)
        } else {
            null to null
        }

        // 3. 파일 매니저 유무에 따라 다른 처리
        return if (tableFileManager != null) {
            // 파일 기반: 논리적 삭제 (tombstone)
            val deletedCount = tableFileManager.deleteRows(tableName, existingTable.dataType, columnName, value)

            // 메모리 테이블 업데이트 (deleted 행 제외)
            tableFileManager.readTable(tableName)?.let { updatedTable ->
                tables[tableName] = updatedTable
            }

            deletedCount
        } else {
            // 메모리 기반: 물리적 삭제 (테스트용)
            var deletedCount = 0
            val updated = tables.compute(tableName) { _, table ->
                table?.let {
                    val remainingRows = it.rows.filter { row ->
                        // WHERE 조건 평가
                        val shouldDelete = if (columnName == null) {
                            true  // WHERE 절 없음 - 모든 행 삭제
                        } else {
                            row[columnName] == value
                        }

                        if (shouldDelete) {
                            deletedCount++
                            false  // 필터링하여 제거
                        } else {
                            true  // 유지
                        }
                    }
                    it.copy(rows = remainingRows)
                }
            }
            deletedCount
        }
    }

    /**
     * 간단한 WHERE 조건 파싱 (column=value 형식만 지원)
     *
     * 지원 형식:
     * - name='Alice'
     * - name="Bob"
     * - id=123
     *
     * @param whereClause WHERE 조건 문자열
     * @return Pair(컬럼명, 값)
     * @throws IllegalArgumentException 잘못된 WHERE 구문
     */
    private fun parseSimpleWhereCondition(whereClause: String): Pair<String, String> {
        val regex = Regex("""(\w+)\s*=\s*(?:'([^']*)'|"([^"]*)"|(\S+))""")
        val match = regex.find(whereClause)
            ?: throw IllegalArgumentException("Invalid WHERE clause: $whereClause")

        val columnName = match.groupValues[1]
        val value = match.groupValues[2].ifEmpty {
            match.groupValues[3].ifEmpty {
                match.groupValues[4]
            }
        }

        return columnName to value
    }

    /**
     * 테이블 조회 (Thread-safe, Disk-based for full table scan)
     *
     * Full table scan: 파일에서 직접 읽어서 최신 데이터 보장
     * - tableFileManager가 있으면 디스크에서 읽기
     * - 없으면 메모리 캐시에서 읽기 (fallback)
     *
     * @param tableName 테이블 이름
     * @return Table 객체 또는 null
     */
    fun select(tableName: String): Table? {
        // 디스크에서 최신 데이터 읽기 (full table scan)
        return tableFileManager?.readTable(tableName) ?: tables[tableName]
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
        val removed = tables.remove(tableName) != null
        if (removed) {
            tableFileManager?.deleteTable(tableName)
        }
        return removed
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
