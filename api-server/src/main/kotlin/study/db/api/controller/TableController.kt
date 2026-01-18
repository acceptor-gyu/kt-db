package study.db.api.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import study.db.api.client.DbClient
import study.db.api.dto.SqlQueryRequest
import study.db.api.util.SqlParser
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest

@RestController
@RequestMapping("/api/tables")
class TableController(
    private val dbClient: DbClient
) {
    // e.g. GET /api/tables/query-plan?query=EXPLAIN SELECT * FROM logs;
    @GetMapping("/query-plan")
    fun findQueryPlan(@RequestParam(required = true) query: String): ResponseEntity<Any> {
        return try {
            val dbRequest = SqlParser.parse(query)
            val response = dbClient.send(dbRequest)

            if (response.success) {
                ResponseEntity.ok(mapOf(
                    "success" to true,
                    "data" to (response.data ?: "")
                ))
            } else {
                ResponseEntity.badRequest().body(mapOf(
                    "success" to false,
                    "message" to (response.message ?: "Failed to get query plan")
                ))
            }
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to "Failed to parse query: ${e.message}"
            ))
        }
    }

    // e.g. POST /api/tables/create
    // Body: { "query": "CREATE TABLE users (id INT, name VARCHAR(100));" }
    @PostMapping("/create")
    fun createTable(@RequestBody request: SqlQueryRequest): ResponseEntity<Map<String, Any>> {
        return try {
            val dbRequest = SqlParser.parse(request.query)
            val response = dbClient.send(dbRequest)

            if (response.success) {
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
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to "Failed to parse query: ${e.message}"
            ))
        }
    }

    // e.g. POST /api/tables/insert
    // Body: { "query": "INSERT INTO users VALUES (id=\"1\", name=\"John\");" }
    @PostMapping("/insert")
    fun insert(@RequestBody request: SqlQueryRequest): ResponseEntity<Map<String, Any>> {
        return try {
            val dbRequest = SqlParser.parse(request.query)
            val response = dbClient.send(dbRequest)

            if (response.success) {
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
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to "Failed to parse query: ${e.message}"
            ))
        }
    }

    // e.g. GET /api/tables/select?query=SELECT * FROM users;
    @GetMapping("/select")
    fun select(@RequestParam(required = true) query: String): ResponseEntity<Map<String, Any>> {
        return try {
            val dbRequest = SqlParser.parse(query)
            val response = dbClient.send(dbRequest)

            if (response.success) {
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
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to "Failed to parse query: ${e.message}"
            ))
        }
    }

    // e.g. DELETE /api/tables/drop?query=DROP TABLE users;
    @DeleteMapping("/drop")
    fun dropTable(@RequestParam(required = true) query: String): ResponseEntity<Map<String, Any>> {
        return try {
            val dbRequest = SqlParser.parse(query)
            val response = dbClient.send(dbRequest)

            if (response.success) {
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
        } catch (e: Exception) {
            ResponseEntity.badRequest().body(mapOf(
                "success" to false,
                "message" to "Failed to parse query: ${e.message}"
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
