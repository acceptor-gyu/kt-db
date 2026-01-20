# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Kotlin/Spring Boot project implementing a **relational database with file-based persistence**. The goal is to learn Kotlin while building a database system from scratch, including networking, concurrency control, and disk I/O.

## Key Features

- **File-based persistence**: Binary format (*.dat files) stored in `./data/`
- **TCP protocol**: String-based SQL protocol over TCP
- **REST API**: HTTP API server as a client interface
- **Thread-safe**: ConcurrentHashMap with atomic operations
- **EXPLAIN support**: Query execution plan analysis with Elasticsearch
- **Multi-client**: Concurrent connections via TCP

## Build & Development Commands

```bash
# Build the project
./gradlew build

# Run db-server (TCP server on port 9000)
./gradlew :db-server:bootRun

# Run api-server (REST API on port 8080)
./gradlew :api-server:bootRun

# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "study.db.server.service.TableServiceTest"

# Clean and rebuild
./gradlew clean build

# Docker Compose (recommended)
export DOCKER_BUILDKIT=1
docker compose up -d --build
```

## Architecture

### Technology Stack
- **Language**: Kotlin 1.9.25
- **Framework**: Spring Boot 3.5.3
- **JDK**: Java 17
- **Build**: Gradle (multi-module project)
- **Monitoring**: Elasticsearch 8.11.1, Kibana 8.11.1
- **Container**: Docker & Docker Compose

### Module Structure

```
kt-db/
├── common/          # Shared code (protocol, data models)
│   ├── protocol/    # ProtocolCodec, DbResponse
│   └── Table.kt     # Table domain model
├── db-server/       # TCP database server
│   ├── DbTcpServer.kt           # TCP server
│   ├── ConnectionHandler.kt     # Per-connection handler
│   ├── service/
│   │   └── TableService.kt      # Table management (thread-safe)
│   ├── storage/
│   │   ├── TableFileManager.kt  # File I/O
│   │   ├── RowEncoder.kt        # Binary encoding
│   │   └── *FieldEncoder.kt     # Type-specific encoders
│   └── elasticsearch/           # EXPLAIN support
└── api-server/      # REST API server (HTTP client)
    ├── TableController.kt       # REST endpoints
    └── DbClient.kt              # TCP client
```

### Core Components

#### 1. **db-server** (Port 9000)
- **DbTcpServer**: Accepts TCP connections
- **ConnectionHandler**: Processes SQL commands (one per connection)
- **TableService**: Manages tables (singleton, thread-safe)
  - In-memory: `ConcurrentHashMap` for fast access
  - Disk: `./data/*.dat` files for persistence
  - SELECT: Reads from disk (full table scan)
- **TableFileManager**: Binary file I/O with atomic writes

#### 2. **api-server** (Port 8080)
- **TableController**: REST API endpoints
- **DbClient**: TCP client to db-server
- Acts as a proxy between HTTP clients and TCP db-server

#### 3. **Storage Layer**
- **File format**: Custom binary format with header, schema, data sections
- **Location**: `./data/` directory (configurable via `db.storage.directory`)
- **Encoding**:
  - INT: 4 bytes (Big Endian)
  - VARCHAR: [2-byte length][UTF-8 bytes]
  - BOOLEAN: 1 byte (0x00/0x01)
  - TIMESTAMP: 8 bytes (Unix milliseconds)

## Data Model

### Table Structure
```kotlin
data class Table(
    val tableName: String,
    val dataType: Map<String, String>,  // column name -> type
    val rows: List<Map<String, String>> // list of rows
)
```

### Supported Data Types
- **INT**: Integer (4 bytes)
- **VARCHAR**: Variable-length string (max 65,535 bytes)
- **BOOLEAN**: true/false (1 byte)
- **TIMESTAMP**: ISO 8601 datetime (8 bytes)

### Supported SQL Commands
- **CREATE TABLE**: `CREATE TABLE users (id INT, name VARCHAR)`
- **INSERT**: `INSERT INTO users VALUES (id="1", name="John")`
- **SELECT**: `SELECT * FROM users` (full table scan only)
- **DROP TABLE**: `DROP TABLE users`
- **EXPLAIN**: `EXPLAIN SELECT * FROM users WHERE name='Alice'`
- **PING**: Connection health check

## Protocol

### String-based TCP Protocol
1. **Client → Server**: SQL string (length-prefixed, UTF-8)
2. **Server → Client**: JSON response (DbResponse)

```
[4-byte length][UTF-8 SQL string]
     ↓
[4-byte length][UTF-8 JSON response]
```

### Example
```kotlin
// Client sends
"CREATE TABLE users (id INT, name VARCHAR)"

// Server responds
{
  "success": true,
  "message": "CREATE TABLE users (id INT, name VARCHAR)",
  "data": null
}
```

## Thread-Safety

### TableService
- Uses `ConcurrentHashMap` for thread-safe access
- Atomic operations:
  - `putIfAbsent()` for CREATE TABLE
  - `compute()` for INSERT
  - `remove()` for DROP TABLE
- File writes use atomic write pattern (temp → sync → rename)

### ConnectionManager
- `AtomicLong` for connection ID generation
- `ConcurrentHashMap` for tracking active connections

## Configuration

### application.properties
```properties
# Storage
db.storage.directory=./data
db.storage.enabled=true

# Server
db.server.port=9000

# Elasticsearch
spring.elasticsearch.uris=http://localhost:9200
```

### Docker Ports
- **Docker**: api-server:8081, db-server:9001, elasticsearch:9201, kibana:5602
- **Local**: api-server:8080, db-server:9000, elasticsearch:9200, kibana:5601

## Testing

### Key Test Files
- `TableServiceTest.kt`: Table operations (26+ tests)
- `TableServicePersistenceTest.kt`: File I/O integration tests
- `DbTcpServerTest.kt`: TCP server connection tests
- `RowEncoderTest.kt`: Binary encoding tests
- `TableFileManagerTest.kt`: File manager tests

### Running Tests
```bash
# All tests
./gradlew test

# Specific module
./gradlew :db-server:test

# Specific test class
./gradlew test --tests "study.db.server.service.TableServiceTest"
```

## Docker Build Optimization

The project uses **layer caching** and **BuildKit cache mounts** to optimize Docker builds:
- First build: ~5 minutes
- Source-only change: ~30 seconds (90% faster)
- Details: See [DOCKER_BUILD_GUIDE.md](./DOCKER_BUILD_GUIDE.md)

## Important Notes

- **Disk I/O**: SELECT always reads from disk to ensure data consistency
- **No WHERE support**: SELECT only supports full table scans
- **No transactions**: No COMMIT/ROLLBACK support
- **No NULL values**: All columns must have values
- **Thread-safe writes**: Multiple connections can write concurrently
- **Atomic file writes**: Crash-safe with temp file pattern

## Documentation

- [README.md](./README.md): Project overview and architecture
- [DOCKER_GUIDE.md](./DOCKER_GUIDE.md): Docker usage guide
- [DOCKER_BUILD_GUIDE.md](./DOCKER_BUILD_GUIDE.md): Build optimization
- [EXPLAIN_GUIDE.md](./EXPLAIN_GUIDE.md): EXPLAIN feature guide
- [PROFILES.md](./PROFILES.md): Spring profiles configuration
