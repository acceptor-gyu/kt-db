# EXPLAIN 기능 사용 가이드

이 문서는 데이터베이스의 EXPLAIN 명령을 테스트하고 사용하는 방법을 설명합니다.

## 목차

1. [준비](#사전-준비)
2. [샘플 데이터 초기화](#샘플-데이터-초기화)
3. [EXPLAIN 예제 실행](#explain-예제-실행)
4. [테스트 시나리오](#테스트-시나리오)
5. [통합 테스트 실행](#통합-테스트-실행)
6. [TCP 클라이언트로 EXPLAIN 실행](#tcp-클라이언트로-explain-실행)

---

## 사전 준비

### 1. Elasticsearch 실행

EXPLAIN 기능은 Elasticsearch를 사용하여 메타데이터를 저장하고 조회합니다.

```bash
# Docker Compose로 Elasticsearch 실행
docker-compose up -d elasticsearch

# Elasticsearch 상태 확인
curl http://localhost:9200/_cluster/health
```

### 2. Elasticsearch 인덱스 초기화

```bash
# 인덱스 생성 (최초 1회만 실행)
./gradlew :db-server:runInitElasticsearch

# 강제로 재생성 (기존 데이터 삭제)
./gradlew :db-server:runInitElasticsearch -Pargs="--force"
```

생성되는 인덱스:
- `db-query-logs` - 쿼리 실행 로그
- `db-table-metadata` - 테이블 메타데이터
- `db-index-metadata` - 인덱스 메타데이터
- `db-table-statistics` - 테이블 통계
- `db-query-plans` - 쿼리 실행 계획

---

## 샘플 데이터 초기화

EXPLAIN 테스트를 위한 샘플 메타데이터를 생성합니다.

```bash
./gradlew :db-server:runInitSampleData
```

### 생성되는 테이블 (RDBMS)

#### 1. users 테이블 (100,000 rows)

| Column | Type    | Description |
|--------|---------|-------------|
| id     | INT     | Primary key |
| name   | VARCHAR | 사용자 이름   |
| email  | VARCHAR | 이메일 (거의 unique) |
| age    | INT     | 나이 (20~80) |
| city   | VARCHAR | 도시 (50개) |

**인덱스:**
- `idx_email` (email) - 매우 높은 카디널리티
- `idx_age` (age) - 낮은 카디널리티
- `idx_name_email` (name, email) - 복합 인덱스 (Covered Query 테스트용)

**통계:**
- Total rows: 100,000
- email distinct: 99,500 (selectivity ≈ 0.001%)
- name distinct: 50,000 (selectivity ≈ 0.002%)
- age distinct: 60 (selectivity ≈ 1.67%)

#### 2. orders 테이블 (500,000 rows)

| Column     | Type      | Description |
|------------|-----------|-------------|
| id         | INT       | Primary key |
| user_id    | INT       | 사용자 ID (FK) |
| product_id | INT       | 상품 ID (FK) |
| quantity   | INT       | 수량 |
| status     | VARCHAR   | 주문 상태 |
| created_at | TIMESTAMP | 생성 시간 |

**인덱스:**
- `idx_user_id` (user_id) - Foreign key
- `idx_status` (status) - 낮은 카디널리티 (5개 값)
- `idx_created_at` (created_at) - 타임스탬프

**통계:**
- Total rows: 500,000
- user_id distinct: 100,000 (selectivity ≈ 0.001%)
- status distinct: 5 (selectivity = 20%)
- created_at distinct: 365,000

#### 3. products 테이블 (10,000 rows)

| Column   | Type    | Description |
|----------|---------|-------------|
| id       | INT     | Primary key |
| name     | VARCHAR | 상품명 |
| category | VARCHAR | 카테고리 |
| price    | DECIMAL | 가격 |
| stock    | INT     | 재고 |

**인덱스:**
- `idx_category` (category) - 20개 카테고리
- `idx_price` (price)

**통계:**
- Total rows: 10,000
- category distinct: 20 (selectivity = 5%)
- price distinct: 5,000

---

## EXPLAIN 예제 실행

샘플 데이터를 초기화한 후 EXPLAIN 예제를 실행합니다.

```bash
./gradlew :db-server:runExplainExample
```

### 출력 예시

```
================================================================================
📊 Scenario 1: INDEX_SCAN (Low Selectivity)
================================================================================
SQL: SELECT * FROM users WHERE email = 'acceptor@example.com'

✅ Query Plan Generated:
   Plan ID: 12345678-1234-1234-1234-123456789abc
   Query Hash: a1b2c3d4e5f6...
   Estimated Cost: 10.5
   Estimated Rows: 1
   Is Covered Query: false

   Step 1: INDEX_SCAN
      Table: users
      Index Used: idx_email
      Filter: email = 'acceptor@example.com'
      Columns: [id, name, email, age, city]
      Cost: 10.5
      Rows: 1
      Is Covered: false
      Description: Using index 'idx_email' (selectivity: 0.10%). Low selectivity

💡 Expected: INDEX_SCAN using idx_email (selectivity ≈ 0.001%)
   Actual: INDEX_SCAN using idx_email
```

---

## 테스트 시나리오

### 시나리오 1: INDEX_SCAN (Low Selectivity)

```sql
SELECT * FROM users WHERE email = '@example.com'
```

- **Selectivity**: 1/99,500 ≈ 0.001%
- **Expected**: INDEX_SCAN using `idx_email`
- **이유**: 매우 낮은 selectivity로 인덱스 사용이 효율적

### 시나리오 2: TABLE_SCAN (High Selectivity)

```sql
SELECT * FROM users WHERE age = 30
```

- **Selectivity**: 1/60 ≈ 1.67%
- **Expected**: TABLE_SCAN
- **이유**: 인덱스가 있지만 selectivity가 높아서 Full Scan이 더 빠름

### 시나리오 3: COVERED_INDEX_SCAN ✅

```sql
SELECT name, email FROM users WHERE name = 'acceptor'
```

- **Index**: `idx_name_email` (name, email)
- **Expected**: COVERED_INDEX_SCAN
- **이유**: SELECT 절의 모든 컬럼이 인덱스에 포함되어 테이블 접근 불필요
- **장점**: 매우 효율적! (VERY EFFICIENT 표시)

### 시나리오 4: INDEX_SCAN (Not Covered)

```sql
SELECT name, email, age FROM users WHERE name = 'acceptor'
```

- **Index**: `idx_name_email` (name, email)
- **Expected**: INDEX_SCAN
- **이유**: `age`가 인덱스에 없어서 테이블 접근 필요

### 시나리오 5: Full TABLE_SCAN

```sql
SELECT * FROM users
```

- **Expected**: Full TABLE_SCAN
- **이유**: WHERE 절이 없어서 전체 테이블 스캔

### 시나리오 6: INDEX_SCAN (Foreign Key)

```sql
SELECT * FROM orders WHERE user_id = 12345
```

- **Selectivity**: 1/100,000 ≈ 0.001%
- **Expected**: INDEX_SCAN using `idx_user_id`
- **이유**: Foreign key 조회는 매우 낮은 selectivity

### 시나리오 7: TABLE_SCAN (Low Cardinality)

```sql
SELECT * FROM orders WHERE status = 'PENDING'
```

- **Selectivity**: 1/5 = 20%
- **Expected**: TABLE_SCAN
- **이유**: selectivity가 15% threshold를 초과

### 시나리오 8: INDEX_SCAN (Category)

```sql
SELECT * FROM products WHERE category = 'Electronics'
```

- **Selectivity**: 1/20 = 5%
- **Expected**: INDEX_SCAN using `idx_category`
- **이유**: selectivity가 threshold 이하

---

## 통합 테스트 실행

```bash
# 전체 테스트 실행
./gradlew :db-server:test

# EXPLAIN 통합 테스트만 실행
./gradlew :db-server:test --tests "study.db.server.elasticsearch.ExplainIntegrationTest"
```

### 테스트 커버리지

- INDEX_SCAN 시나리오
- TABLE_SCAN 시나리오
- COVERED_INDEX_SCAN 시나리오
- 비용 계산 검증
- 에러 처리 검증
- QueryPlan 영속성 검증

---

## TCP 클라이언트로 EXPLAIN 실행

### Kotlin 클라이언트 예제

```kotlin
import study.db.common.protocol.DbCommand
import study.db.common.protocol.DbRequest
import study.db.common.protocol.ProtocolCodec
import java.net.Socket

fun main() {
    Socket("localhost", 9000).use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // EXPLAIN 요청 생성
        val request = DbRequest(
            command = DbCommand.EXPLAIN,
            sql = "SELECT * FROM users WHERE email = 'acceptor@example.com'"
        )

        // 요청 전송
        val requestBytes = ProtocolCodec.encodeRequest(request)
        ProtocolCodec.writeMessage(output, requestBytes)

        // 응답 수신
        val responseBytes = ProtocolCodec.readMessage(input)
        val response = ProtocolCodec.decodeResponse(responseBytes)

        if (response.success) {
            println("Query Plan:")
            println(response.data)
        } else {
            println("Error: ${response.message}")
        }
    }
}
```

### 응답 JSON 예시

```json
{
  "planId": "12345678-1234-1234-1234-123456789abc",
  "queryText": "SELECT * FROM users WHERE email = 'acceptor@example.com'",
  "queryHash": "a1b2c3d4e5f6...",
  "executionSteps": [
    {
      "stepId": 1,
      "stepType": "INDEX_SCAN",
      "tableName": "users",
      "indexUsed": "idx_email",
      "filterCondition": "email = 'acceptor@example.com'",
      "columnsAccessed": ["id", "name", "email", "age", "city"],
      "estimatedCost": 10.5,
      "estimatedRows": 1,
      "isCovered": false,
      "description": "Using index 'idx_email' (selectivity: 0.10%). Low selectivity"
    }
  ],
  "estimatedCost": 10.5,
  "estimatedRows": 1,
  "isCoveredQuery": false,
  "generatedAt": "2026-01-15T10:30:00Z"
}
```

---

## EXPLAIN 결과 해석

### Step Type

| Type | 설명 | 성능 |
|------|------|------|
| COVERED_INDEX_SCAN | 인덱스만으로 쿼리 처리 가능 | ⭐⭐⭐ 매우 빠름 |
| INDEX_SCAN | 인덱스 사용 후 테이블 접근 | ⭐⭐ 빠름 |
| TABLE_SCAN | 전체 테이블 스캔 | ⭐ 느림 |

### Selectivity

- **< 0.1%**: 매우 낮음 → INDEX_SCAN 매우 효율적
- **0.1% ~ 5%**: 낮음 → INDEX_SCAN 효율적
- **5% ~ 15%**: 중간 → INDEX_SCAN 사용 가능
- **> 15%**: 높음 → TABLE_SCAN이 더 빠름 (threshold)

### Covered Query

✅ **Covered Query 조건:**
1. WHERE 절의 컬럼이 인덱스의 leading column
2. SELECT 절의 모든 컬럼이 인덱스에 포함

**장점:**
- 테이블 접근 불필요
- I/O 대폭 감소
- 매우 빠른 응답 속도

---

## 트러블슈팅

### Elasticsearch 연결 실패

```
Error: Connection refused to localhost:9200
```

**해결:**
```bash
# Elasticsearch 상태 확인
docker-compose ps elasticsearch

# Elasticsearch 재시작
docker-compose restart elasticsearch
```

### 샘플 데이터가 없음

```
Error: Table doesn't exist: users
```

**해결:**
```bash
# 샘플 데이터 재생성
./gradlew :db-server:runInitSampleData
```

### 통합 테스트 실패

```
Error: No statistics found for table: users
```

**해결:**
```bash
# Elasticsearch 인덱스 재생성
./gradlew :db-server:runInitElasticsearch -Pargs="--force"

# 샘플 데이터 재생성
./gradlew :db-server:runInitSampleData

# 테스트 재실행
./gradlew :db-server:test --tests "ExplainIntegrationTest"
```

---

## 추가 학습 자료

### EXPLAIN 내부 동작

1. **SQL 파싱**: `SqlParser`를 통해 SQL 문법 검증
2. **메타데이터 조회**: Elasticsearch에서 테이블/인덱스 정보 조회
3. **통계 조회**: 테이블 통계 및 selectivity 계산
4. **최적화 결정**:
   - Selectivity < 15% → INDEX_SCAN 고려
   - Covered Query 여부 확인
   - 비용 계산 및 최종 결정
5. **QueryPlan 생성**: 실행 계획 생성 및 저장

### Selectivity 계산식

```
Selectivity = 1 / distinctCount

예:
- email: 1/99,500 ≈ 0.001%
- age: 1/60 ≈ 1.67%
- status: 1/5 = 20%
```

### 비용 계산식

```kotlin
// INDEX_SCAN 비용
val indexSeekCost = log2(totalRows)
val dataReadCost = totalRows * selectivity
val totalCost = indexSeekCost + dataReadCost

// TABLE_SCAN 비용
val tableScanCost = totalRows.toDouble()
```

---

## 요약

1. **사전 준비**: Elasticsearch 실행 및 인덱스 초기화
2. **샘플 데이터**: `./gradlew :db-server:runInitSampleData`
3. **예제 실행**: `./gradlew :db-server:runExplainExample`
4. **테스트**: `./gradlew :db-server:test --tests "ExplainIntegrationTest"`
5. **결과 해석**: Step Type, Selectivity, Covered Query 여부 확인

EXPLAIN 기능을 통해 쿼리 최적화 전략을 학습하고 데이터베이스 내부 동작을 이해할 수 있습니다! 🚀
