package study.db.crud.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import study.db.crud.CreateTableRequest

@RestController
class CRUDController {

    @PostMapping("/create")
    fun create(@RequestBody createTableRequest: CreateTableRequest): ResponseEntity<String> {
        // Logic for creating a resource

        // create map like relational database

        return ResponseEntity.ok()
            .body(
                "Resource created successfully. " +
                        "You can use the following SQL queries to create a table and insert data."
            )

        // create table query example
        // return "CREATE TABLE table_name (column1 datatype, column2 datatype, ...)"
        // return "INSERT INTO table_name (column1, column2) VALUES (value1, value2)"



    }
}