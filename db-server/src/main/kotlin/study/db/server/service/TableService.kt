package study.db.server.service

import org.slf4j.LoggerFactory
import study.db.common.Row
import study.db.common.Table
import study.db.common.where.WhereClause
import study.db.common.where.WhereEvaluator
import study.db.server.db_engine.Resolver
import study.db.server.db_engine.dto.OrderByColumn
import study.db.server.exception.ColumnNotFoundException
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
    private val tableFileManager: TableFileManager? = null,
    private var vacuumService: study.db.server.vacuum.VacuumService? = null
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
     * @param whereString WHERE 조건 문자열 (null이면 전체 삭제)
     * @return 삭제된 행 개수
     * @throws IllegalStateException 테이블이 존재하지 않을 때
     * @throws ColumnNotFoundException WHERE 조건에 존재하지 않는 컬럼이 사용됐을 때
     */
    fun delete(tableName: String, whereString: String?): Int {
        // 1. 테이블 존재 여부 확인
        val existingTable = tables[tableName]
            ?: throw IllegalStateException("Table '$tableName' not found")

        // 2. WHERE 조건 파싱 (WhereClause 기반)
        val whereClause = WhereClause.parse(whereString)

        // 3. WHERE 조건 컬럼 존재 검증
        validateWhereColumns(existingTable, whereClause)

        // 4. 파일 매니저 유무에 따라 다른 처리
        return if (tableFileManager != null) {
            // 파일 기반: 논리적 삭제 (tombstone)
            val deletedCount = tableFileManager.deleteRows(tableName, existingTable.dataType, whereClause)

            // 메모리 테이블 업데이트 (deleted 행 제외)
            tableFileManager.readTable(tableName)?.let { updatedTable ->
                tables[tableName] = updatedTable
            }

            deletedCount
        } else {
            // 메모리 기반: 물리적 삭제 (테스트용)
            var deletedCount = 0
            tables.compute(tableName) { _, table ->
                table?.let {
                    val remainingRows = it.rows.filter { row ->
                        val rowObj = Row(data = row)
                        val shouldDelete = WhereEvaluator.matches(rowObj, whereClause, it.dataType)

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
     * WHERE 조건에 사용된 컬럼이 테이블에 존재하는지 검증
     *
     * @param table 대상 테이블
     * @param whereClause WHERE 조건
     * @throws ColumnNotFoundException 존재하지 않는 컬럼이 사용됐을 때
     */
    private fun validateWhereColumns(table: Table, whereClause: WhereClause) {
        val columnNames = whereClause.getColumnNames()
        for (columnName in columnNames) {
            if (!table.dataType.containsKey(columnName)) {
                throw ColumnNotFoundException(table.tableName, columnName)
            }
        }
    }

    /**
     * 테이블 조회 (Thread-safe, Disk-based for full table scan)
     *
     * 실행 파이프라인: WHERE 필터링 → ORDER BY 정렬 → 컬럼 프로젝션
     * - tableFileManager가 있으면 디스크에서 읽기
     * - 없으면 메모리 캐시에서 읽기 (fallback)
     *
     * @param tableName 테이블 이름
     * @param whereString WHERE 절 문자열 (null이면 전체 반환)
     * @param columns 조회할 컬럼 목록 (null 또는 ["*"]이면 전체 컬럼 반환)
     * @param orderBy ORDER BY 컬럼 목록 (null이면 삽입 순서 유지)
     * @return Table 객체 또는 null
     * @throws ColumnNotFoundException WHERE 조건, 컬럼 목록, ORDER BY에 존재하지 않는 컬럼이 사용됐을 때
     */
    fun select(
        tableName: String,
        whereString: String? = null,
        columns: List<String>? = null,
        orderBy: List<OrderByColumn>? = null,
        limit: Int? = null,
        offset: Int? = null
    ): Table? {
        // 디스크에서 최신 데이터 읽기 (full table scan)
        val table = tableFileManager?.readTable(tableName) ?: tables[tableName] ?: return null

        // 1. WHERE 필터링
        val filteredRows = if (whereString == null) {
            table.rows
        } else {
            val whereClause = WhereClause.parse(whereString)
            validateWhereColumns(table, whereClause)
            table.rows.filter { row ->
                WhereEvaluator.matches(Row(data = row), whereClause, table.dataType)
            }
        }

        // 2. ORDER BY 정렬 (프로젝션 전 - 정렬 컬럼이 SELECT 컬럼에 없을 수 있으므로)
        val sortedRows = if (orderBy.isNullOrEmpty()) {
            filteredRows
        } else {
            Resolver.validateOrderByColumns(table, orderBy)
            filteredRows.sortedWith(
                Comparator { row1, row2 ->
                    for (col in orderBy) {
                        val v1 = row1[col.columnName] ?: ""
                        val v2 = row2[col.columnName] ?: ""
                        val colType = table.dataType[col.columnName]?.uppercase() ?: "VARCHAR"
                        val cmp = WhereEvaluator.compareValues(v1, v2, colType)
                        if (cmp != 0) return@Comparator if (col.ascending) cmp else -cmp
                    }
                    0
                }
            )
        }

        // 3. OFFSET/LIMIT 적용 (정렬 후, 프로젝션 전)
        val pagedRows = sortedRows
            .let { if (offset != null) it.drop(offset) else it }
            .let { if (limit != null) it.take(limit) else it }

        // 4. 컬럼 프로젝션 (OFFSET/LIMIT 후)
        if (columns == null || columns == listOf("*")) {
            return table.copy(rows = pagedRows)
        }

        Resolver.validateSelectColumns(table, columns)

        val projectedRows = pagedRows.map { row ->
            row.filterKeys { it in columns }
        }
        val projectedDataType = table.dataType.filterKeys { it in columns }

        return table.copy(rows = projectedRows, dataType = projectedDataType)
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

    /**
     * VACUUM 실행 (삭제된 행 물리적 제거)
     *
     * @param tableName 테이블 이름
     * @return VacuumStats 통계 객체
     */
    fun vacuum(tableName: String): study.db.common.VacuumStats {
        if (!tableExists(tableName)) {
            return study.db.common.VacuumStats.failure("Table '$tableName' not found")
        }

        if (vacuumService == null) {
            return study.db.common.VacuumStats.failure("VACUUM service is not available")
        }

        val stats = vacuumService!!.vacuumTable(tableName)

        // VACUUM 성공 시 메모리 테이블 업데이트
        if (stats.success) {
            tableFileManager?.readTable(tableName)?.let { updatedTable ->
                tables[tableName] = updatedTable
            }
        }

        return stats
    }

    /**
     * VacuumService 설정 (Setter injection for circular dependency)
     */
    fun setVacuumService(service: study.db.server.vacuum.VacuumService) {
        this.vacuumService = service
    }
}
