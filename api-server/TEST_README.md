# API 테스트 가이드

CREATE, INSERT, EXPLAIN 등의 API를 테스트할 수 있는 다양한 방법을 제공합니다.

## 테스트 준비

### 1. 서버 실행

```bash
# Terminal 1: DB 서버 실행
./gradlew :db-server:bootRun

# Terminal 2: API 서버 실행
./gradlew :api-server:bootRun
```

서버가 실행되면 API 서버는 `http://localhost:8080`에서 접근 가능합니다.

## 테스트 방법

### 방법 1: 자동화 스크립트 (추천)

가장 쉬운 방법입니다. 모든 테스트 시나리오를 자동으로 실행합니다.

```bash
cd api-server
./test-api-requests.sh
```

**실행 내용:**
- 5개 테이블 생성 (users, products, orders, logs, employees)
- 19개 레코드 삽입
- 각 테이블별 SELECT 조회
- 5개 EXPLAIN 쿼리 실행

**필요한 도구:**
- `jq` (JSON 포맷팅용)
  ```bash
  # macOS
  brew install jq

  # Ubuntu/Debian
  sudo apt-get install jq
  ```

### 방법 2: Postman 사용

Postman에서 시각적으로 테스트할 수 있습니다.

1. Postman 실행
2. Import → `postman-collection.json` 선택
3. 컬렉션이 로드되면 순서대로 실행

**컬렉션 구성:**
- 0. Ping - DB 서버 연결 확인
- 1. Users - 사용자 테이블 (7개 요청)
- 2. Products - 상품 테이블 (8개 요청)
- 3. Orders - 주문 테이블 (6개 요청)
- 4. Logs - 로그 테이블 (6개 요청)
- 5. Employees - 직원 테이블 (6개 요청)
- 9. Cleanup - 테이블 삭제 (5개 요청)

### 방법 3: 수동 curl 명령

개별 테스트가 필요할 때 사용합니다.

#### Ping 테스트
```bash
curl -X GET http://localhost:8080/api/tables/ping
```

#### CREATE TABLE
```bash
curl -X POST http://localhost:8080/api/tables/create \
  -H "Content-Type: application/json" \
  -d '{
    "query": "CREATE TABLE test (id INT, name VARCHAR)"
  }'
```

#### INSERT
```bash
curl -X POST http://localhost:8080/api/tables/insert \
  -H "Content-Type: application/json" \
  -d '{
    "query": "INSERT INTO test VALUES (id=\"1\", name=\"Alice\")"
  }'
```

#### SELECT
```bash
curl -X GET 'http://localhost:8080/api/tables/select?query=SELECT%20*%20FROM%20test'
```

또는:
```bash
curl -X GET http://localhost:8080/api/tables/select \
  --data-urlencode "query=SELECT * FROM test"
```

#### EXPLAIN
```bash
curl -X GET 'http://localhost:8080/api/tables/query-plan?query=EXPLAIN%20SELECT%20*%20FROM%20test'
```

또는:
```bash
curl -X GET http://localhost:8080/api/tables/query-plan \
  --data-urlencode "query=EXPLAIN SELECT * FROM test"
```

#### DROP TABLE
```bash
curl -X DELETE 'http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20test'
```

## 테스트 시나리오 상세

### 시나리오 1: Users (사용자)
**테이블 구조:** `id INT, name VARCHAR, email VARCHAR, age INT, active BOOLEAN`

**샘플 데이터:**
- John Doe (id=1, age=30, active=true)
- Jane Smith (id=2, age=25, active=true)
- Bob Wilson (id=3, age=35, active=false)
- Alice Brown (id=4, age=28, active=true)

### 시나리오 2: Products (상품)
**테이블 구조:** `id INT, name VARCHAR, price INT, stock INT, available BOOLEAN`

**샘플 데이터:**
- Laptop (id=101, price=1200, stock=15)
- Wireless Mouse (id=102, price=25, stock=50)
- Mechanical Keyboard (id=103, price=80, stock=0, available=false)
- 4K Monitor (id=104, price=350, stock=8)
- Bluetooth Headphones (id=105, price=120, stock=20)

### 시나리오 3: Orders (주문)
**테이블 구조:** `order_id INT, user_id INT, product_id INT, quantity INT, total_price INT`

**샘플 데이터:**
- Order 1001: John이 Laptop 구매 (1200원)
- Order 1002: Jane이 Mouse 2개 구매 (50원)
- Order 1003: John이 Monitor 구매 (350원)

### 시나리오 4: Logs (로그, TIMESTAMP 포함)
**테이블 구조:** `log_id INT, user_id INT, action VARCHAR, created_at TIMESTAMP`

**샘플 데이터:**
- Log 1: John 로그인 (2024-01-15 10:30)
- Log 2: John 상품 조회 (2024-01-15 10:35)
- Log 3: John 구매 (2024-01-15 10:40)

### 시나리오 5: Employees (직원, 모든 타입 포함)
**테이블 구조:** `emp_id INT, name VARCHAR, department VARCHAR, salary INT, hired_at TIMESTAMP`

**샘플 데이터:**
- David Lee (Engineering, salary=90000)
- Sarah Kim (Marketing, salary=75000)
- Michael Park (Engineering, salary=95000)

## 데이터 확인

### 파일 시스템 확인
테스트 실행 후 `./data` 디렉토리를 확인하면 바이너리 파일들이 생성됩니다:

```bash
ls -lh ./data/

# 출력 예시:
# users.dat
# products.dat
# orders.dat
# logs.dat
# employees.dat
```

### DB 서버 재시작 테스트
서버를 재시작해도 데이터가 유지되는지 확인:

```bash
# 1. 테스트 스크립트 실행
./test-api-requests.sh

# 2. DB 서버 재시작 (Ctrl+C 후 재실행)
./gradlew :db-server:bootRun

# 3. 데이터 조회 확인
curl -X GET 'http://localhost:8080/api/tables/select?query=SELECT%20*%20FROM%20users'
```

## 테스트 데이터 정리

### 개별 테이블 삭제
```bash
curl -X DELETE 'http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20users'
curl -X DELETE 'http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20products'
curl -X DELETE 'http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20orders'
curl -X DELETE 'http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20logs'
curl -X DELETE 'http://localhost:8080/api/tables/drop?query=DROP%20TABLE%20employees'
```

### 파일 직접 삭제
```bash
rm -rf ./data/*.dat
```

## 추가 참고 자료

- `API_USAGE.md` - API 전체 사용법
- `test-data.json` - 모든 테스트 데이터 JSON 형식
- `postman-collection.json` - Postman 컬렉션 파일

## 트러블슈팅

### DB 서버 연결 안 됨
```json
{
  "success": false,
  "message": "DB Server is not available: Connection refused"
}
```
**해결:** DB 서버가 실행 중인지 확인 (`./gradlew :db-server:bootRun`)

### SQL 파싱 에러
```json
{
  "success": false,
  "message": "Failed to parse query: Invalid CREATE TABLE syntax"
}
```
**해결:** SQL 구문 확인 (세미콜론은 선택사항, 대소문자 구분 없음)

### 테이블 없음 에러
```json
{
  "success": false,
  "message": "Table 'users' not found"
}
```
**해결:** CREATE TABLE을 먼저 실행

## 성능 테스트

대량 데이터 삽입 테스트:

```bash
# 100개 레코드 삽입
for i in {1..100}; do
  curl -s -X POST http://localhost:8080/api/tables/insert \
    -H "Content-Type: application/json" \
    -d "{\"query\": \"INSERT INTO users VALUES (id=\\\"$i\\\", name=\\\"User$i\\\", email=\\\"user$i@example.com\\\", age=\\\"$((20 + i % 50))\\\", active=\\\"true\\\")\"}"
  echo "Inserted user $i"
done
```

조회 성능 테스트:
```bash
time curl -X GET 'http://localhost:8080/api/tables/select?query=SELECT%20*%20FROM%20users'
```
