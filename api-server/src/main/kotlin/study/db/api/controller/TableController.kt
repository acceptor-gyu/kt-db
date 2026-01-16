package study.db.api.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import study.db.api.client.DbClient
import study.db.api.dto.FindQueryPlanResponse
import study.db.api.dto.SqlQueryRequest
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest

@RestController
@RequestMapping("/api/tables")
class TableController(
    private val dbClient: DbClient
) {
    // e.g. GET /api/tables/query-plan?query=EXPLAIN SELECT * FROM logs;
    @GetMapping("/query-plan")
    fun findQueryPlan(@RequestParam(required = true) query: String): ResponseEntity<FindQueryPlanResponse> {
        // 더미 데이터 반환
        val dummyResponse = FindQueryPlanResponse(
            id = 1,
            selectType = "SIMPLE",
            table = "logs",
            type = "ALL",
            possibleKeys = listOf("idx_user_id", "idx_created_at"),
            key = null,
            keyLen = null,
            rows = 1000,
            extra = "Using where"
        )

        return ResponseEntity.ok(dummyResponse)
    }

    // e.g. POST /api/tables/create
    // Body: { "query": "CREATE TABLE users (id INT, name VARCHAR(100));" }
    @PostMapping("/create")
    fun createTable(@RequestBody request: SqlQueryRequest): ResponseEntity<Map<String, Any>> {
        // TODO: SQL 쿼리 파싱하여 DbRequest로 변환
        val dbRequest = DbRequest(
            command = DbCommand.CREATE_TABLE,
            tableName = "", // SQL 파싱 필요
            columns = null
        )

        val response = dbClient.send(dbRequest)

        return if (response.success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "message" to (response.message ?: "Table created"),
                "data" to (response.data ?: "")
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to (response.message ?: "Failed to create table"),
                "errorCode" to (response.errorCode ?: -1)
            ))
        }
    }

    // e.g. POST /api/tables/insert
    // Body: { "query": "INSERT INTO users VALUES (1, 'John');" }
    @PostMapping("/insert")
    fun insert(@RequestBody request: SqlQueryRequest): ResponseEntity<Map<String, Any>> {
        // TODO: SQL 쿼리 파싱하여 DbRequest로 변환
        val dbRequest = DbRequest(
            command = DbCommand.INSERT,
            tableName = "", // SQL 파싱 필요
            values = null
        )

        val response = dbClient.send(dbRequest)

        return if (response.success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "message" to (response.message ?: "Data inserted")
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to (response.message ?: "Failed to insert data")
            ))
        }
    }

    // e.g. GET /api/tables/select?query=SELECT * FROM users;
    @GetMapping("/select")
    fun select(@RequestParam(required = true) query: String): ResponseEntity<Map<String, Any>> {
        // TODO: SQL 쿼리 파싱하여 DbRequest로 변환
        val dbRequest = DbRequest(
            command = DbCommand.SELECT,
            tableName = "" // SQL 파싱 필요
        )

        val response = dbClient.send(dbRequest)

        return if (response.success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "data" to (response.data ?: "")
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to (response.message ?: "Failed to select data")
            ))
        }
    }

    // e.g. DELETE /api/tables/drop?query=DROP TABLE users;
    @DeleteMapping("/drop")
    fun dropTable(@RequestParam(required = true) query: String): ResponseEntity<Map<String, Any>> {
        // TODO: SQL 쿼리 파싱하여 DbRequest로 변환
        val dbRequest = DbRequest(
            command = DbCommand.DROP_TABLE,
            tableName = "" // SQL 파싱 필요
        )

        val response = dbClient.send(dbRequest)

        return if (response.success) {
            ResponseEntity.ok(mapOf(
                "success" to true,
                "message" to (response.message ?: "Table dropped")
            ))
        } else {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to (response.message ?: "Failed to drop table")
            ))
        }
    }

    @GetMapping("/ping")
    fun ping(): ResponseEntity<Map<String, Any>> {
        return try {
            val dbRequest = DbRequest(command = DbCommand.PING)
            val response = dbClient.send(dbRequest)

            ResponseEntity.ok(mapOf(
                "success" to response.success,
                "message" to (response.message ?: "pong")
            ))
        } catch (e: Exception) {
            ResponseEntity.ok(mapOf(
                "success" to false,
                "message" to "DB Server is not available: ${e.message}"
            ))
        }
    }
}
