# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Kotlin/Spring Boot project implementing an in-memory database. The goal is to learn Kotlin while building a simple relational database system from scratch.

## Build & Development Commands

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Run a specific test class
./gradlew test --tests "study.db.DbApplicationTests"

# Run a specific test method
./gradlew test --tests "study.db.DbApplicationTests.contextLoads"

# Clean and rebuild
./gradlew clean build
```

## Architecture

### Technology Stack
- Kotlin 1.9.25 with Spring Boot 3.5.3
- Java 17 toolchain
- JUnit 5 for testing

### Code Structure

The application is organized under `study.db` package:

- **crud/** - Core CRUD operations for the in-memory database
  - `CreateTableRequest` - DTO for table creation (table name, columns with types, values)
  - `Table` - Domain model representing a database table
  - **controller/** - REST endpoints (`CRUDController` handles `/create` POST)
  - **service/** - Business logic (`TableService` manages in-memory table storage)

### Data Model

Tables are stored in-memory using a `MutableMap<String, Table>`. Each table contains:
- Table name
- Column definitions (name to data type mapping)
- Row values

The system generates SQL-like query strings (CREATE TABLE, INSERT INTO) based on operations.
