# API 사용 가이드

API 서버를 통해 SQL 쿼리를 실행할 수 있습니다.

## 서버 실행

```bash
# DB 서버 실행
./gradlew :db-server:bootRun

# API 서버 실행 (별도 터미널)
./gradlew :api-server:bootRun
```

## API 엔드포인트

### 1. CREATE TABLE

테이블을 생성합니다.

**요청:**
```bash
curl -X POST http://localhost:8080/api/tables/create \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE users (id INT, name VARCHAR, age INT)"
  }'
```

**응답 (성공):**
```json
{
  "success": true,
  "message": "Table created",
  "data": "CREATE TABLE users (id INT, name VARCHAR, age INT)"
}
```

**응답 (실패):**
```json
{
  "success": false,
  "message": "Failed to create table",
  "errorCode": -1
}
```

### 2. INSERT

데이터를 삽입합니다.

**요청:**
```bash
curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"1\", name=\"John\", age=\"30\")"
  }'
```

**요청 (single quotes):**
```bash
curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d "{
    \"query\": \"INSERT INTO users VALUES (id='2', name='Jane', age='25')\"
  }"
```

**요청 (quotes 없음):**
```bash
curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=3, name=Bob, age=35)"
  }'
```

**응답 (성공):**
```json
{
  "success": true,
  "message": "Data inserted"
}
```

### 3. SELECT

데이터를 조회합니다.

**요청:**
```bash
curl -X GET "http://localhost:8080/api/tables/select?query=SELECT%20*%20FROM%20users"
```

또는:
```bash
curl -X GET http://localhost:8080/api/tables/select \
  --data-urlencode "query=SELECT * FROM users"
```

**응답 (성공):**
```json
{
  "success": true,
  "data": "Table: users\nColumns: [id, name, age]\nRows:\n  Row 1: {id=1, name=John, age=30}\n  Row 2: {id=2, name=Jane, age=25}"
}
```

### 4. DROP TABLE

테이블을 삭제합니다.

**요청:**
```bash
curl -X DELETE "http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20users"
```

또는:
```bash
curl -X DELETE http://localhost:8080/api/tables/drop \
  --data-urlencode "query=DROP TABLE users"
```

**응답 (성공):**
```json
{
  "success": true,
  "message": "Table dropped"
}
```

### 5. EXPLAIN (Query Plan)

쿼리 실행 계획을 조회합니다.

**요청:**
```bash
curl -X GET "http://localhost:8080/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20users"
```

또는:
```bash
curl -X GET http://localhost:8080/api/tables/query-plan \
  --data-urlencode "query=EXPLAIN SELECT * FROM users"
```

**응답 (성공):**
```json
{
  "success": true,
  "data": "Query execution plan details..."
}
```

### 6. PING

DB 서버 연결 상태를 확인합니다.

**요청:**
```bash
curl -X GET http://localhost:8080/api/tables/ping
```

**응답 (성공):**
```json
{
  "success": true,
  "message": "pong"
}
```

**응답 (DB 서버 연결 불가):**
```json
{
  "success": false,
  "message": "DB Server is not available: Connection refused"
}
```

## 지원하는 SQL 구문

### CREATE TABLE
```sql
CREATE TABLE <table_name> (
  <column1> <type1>,
  <column2> <type2>,
  ...
)
```

**예제:**
- `CREATE TABLE users (id INT, name VARCHAR)`
- `CREATE TABLE products (id INT, name VARCHAR, price INT, available BOOLEAN)`

### INSERT INTO
```sql
INSERT INTO <table_name> VALUES (
  <column1>="<value1>",
  <column2>='<value2>',
  <column3>=<value3>
)
```

**예제:**
- `INSERT INTO users VALUES (id="1", name="John")`
- `INSERT INTO users VALUES (id='2', name='Jane')`
- `INSERT INTO users VALUES (id=3, name=Bob)`

### SELECT
```sql
SELECT * FROM <table_name>
SELECT <columns> FROM <table_name> WHERE <condition>
```

**예제:**
- `SELECT * FROM users`
- `SELECT id, name FROM users WHERE id="1"`

### DROP TABLE
```sql
DROP TABLE <table_name>
```

**예제:**
- `DROP TABLE users`

### EXPLAIN
```sql
EXPLAIN <query>
```

**예제:**
- `EXPLAIN SELECT * FROM users`
- `EXPLAIN SELECT id, name FROM users WHERE age > 20`

## 전체 사용 예제

```bash
# 1. DB 서버 연결 확인
curl -X GET http://localhost:8080/api/tables/ping

# 2. 테이블 생성
curl -X POST http://localhost:8080/api/tables/create \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE users (id INT, name VARCHAR, age INT, active BOOLEAN)"
  }'

# 3. 데이터 삽입
curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"1\", name=\"John\", age=\"30\", active=\"true\")"
  }'

curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO users VALUES (id=\"2\", name=\"Jane\", age=\"25\", active=\"false\")"
  }'

# 4. 데이터 조회
curl -X GET http://localhost:8080/api/tables/select \
  --data-urlencode "query=SELECT * FROM users"

# 5. 쿼리 플랜 확인
curl -X GET http://localhost:8080/api/tables/query-plan \
  --data-urlencode "query=EXPLAIN SELECT * FROM users"

# 6. 테이블 삭제
curl -X DELETE http://localhost:8080/api/tables/drop \
  --data-urlencode "query=DROP TABLE users"
```

## 파일 저장 위치

- 테이블 데이터는 `./data/{tableName}.dat` 형식으로 바이너리 파일로 저장됩니다.
- 서버 재시작 시 자동으로 모든 테이블이 로드됩니다.

## 에러 처리

모든 API는 다음과 같은 형식으로 에러를 반환합니다:

```json
{
  "success": false,
  "message": "Failed to parse query: Invalid CREATE TABLE syntax: CREATE TABLE"
}
```

일반적인 에러:
- SQL 파싱 에러: 잘못된 SQL 구문
- DB 서버 연결 에러: DB 서버가 실행되지 않음
- 테이블 없음: 존재하지 않는 테이블에 접근
- 타입 불일치: 잘못된 데이터 타입
