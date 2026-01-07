package study.db.api.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import study.db.api.client.DbClient
import study.db.common.CreateTableRequest
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest

@RestController
@RequestMapping("/api/tables")
class TableController(
    private val dbClient: DbClient
) {
    @PostMapping
    fun createTable(@RequestBody request: CreateTableRequest): ResponseEntity<Map<String, Any>> {
        val dbRequest = DbRequest(
            command = DbCommand.CREATE_TABLE,
            tableName = request.tableName,
            columns = request.columns
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

    @PostMapping("/{tableName}/insert")
    fun insert(
        @PathVariable tableName: String,
        @RequestBody values: Map<String, String>
    ): ResponseEntity<Map<String, Any>> {
        val dbRequest = DbRequest(
            command = DbCommand.INSERT,
            tableName = tableName,
            values = values
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

    @GetMapping("/{tableName}")
    fun select(@PathVariable tableName: String): ResponseEntity<Map<String, Any>> {
        val dbRequest = DbRequest(
            command = DbCommand.SELECT,
            tableName = tableName
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

    @DeleteMapping("/{tableName}")
    fun dropTable(@PathVariable tableName: String): ResponseEntity<Map<String, Any>> {
        val dbRequest = DbRequest(
            command = DbCommand.DROP_TABLE,
            tableName = tableName
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
