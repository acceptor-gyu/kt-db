# DELETE Record 기능 개선 계획

## 0. 요구사항 정제 (Requirement Refinement)

### 목표 요약
DELETE record 기능의 완성도를 높이고, 현재 구현의 한계를 개선하여 실용적인 수준의 DELETE 연산을 제공한다.

### 사용자 관점
- SQL 표준에 가까운 `DELETE FROM table WHERE condition` 문법 지원
- 다양한 WHERE 조건 (비교 연산자, AND/OR 조합) 지원
- DELETE 후 SELECT 시 삭제된 행이 보이지 않아야 함
- REST API를 통한 DELETE 명령 실행 가능

### 시스템 관점
- Tombstone 방식의 논리적 삭제 (현재 구현 유지)
- VACUUM을 통한 물리적 삭제 (현재 구현 유지)
- Buffer Pool 캐시 무효화 처리
- 동시성 안전 보장 (ConcurrentHashMap + atomic 연산)

### 기능 요구사항
1. 복합 WHERE 조건 지원 (AND, OR, 비교 연산자)
2. 타입 기반 WHERE 평가 (INT는 숫자 비교, VARCHAR는 문자열 비교)
3. DELETE 결과에 삭제된 행 수 반환
4. REST API DELETE 엔드포인트 추가

### 비기능 요구사항
1. DELETE 연산의 스레드 안전성
2. 파일 쓰기의 원자성 (atomic write)
3. 에러 발생 시 데이터 일관성 보장

### 암묵적 요구사항
- DELETE 후 메모리(ConcurrentHashMap)와 디스크(.dat 파일) 간 데이터 일관성 유지
- 대량 DELETE 시 성능 저하 최소화
- VACUUM과의 연계 동작 보장

### 누락된 요구사항 후보
- DELETE 결과 응답에 삭제 전/후 행 수 포함 (선택적)
- DELETE 실행 시 Buffer Pool 무효화 여부 확인
- 존재하지 않는 컬럼에 대한 WHERE 조건 에러 처리

---

## 1. 구현 현황 (Implementation Status)

### 1.1 구현 완료 영역

#### SQL 파싱 및 라우팅

| 구성요소 | 파일 | 설명 |
|---------|------|------|
| DELETE 파싱 | `ConnectionHandler.kt:432` | Regex 기반 파싱. `DELETE FROM {table} [WHERE {조건}]` 형식 지원 |
| 명령 라우팅 | `ConnectionHandler.kt:318, 431-446` | DELETE 명령 인식 후 `parseAndHandleDelete()` 호출 |

- JSqlParser가 아닌 **단순 Regex**로 파싱한다. SELECT만 JSqlParser를 사용한다.
- WHERE 절 추출 후 `TableService.delete(tableName, whereString)`에 전달한다.

#### DELETE 실행 엔진

| 구성요소 | 파일 | 설명 |
|---------|------|------|
| TableService.delete() | `TableService.kt:144-187` | WHERE 파싱, 컬럼 검증, 삭제 실행 |
| TableFileManager.deleteRows() | `TableFileManager.kt:587-618` | 파일 기반 tombstone 마킹 |

**실행 경로가 2개** 존재한다:

1. **파일 기반** (`tableFileManager != null`): `TableFileManager.deleteRows()` 호출. Row의 `deleted` 플래그를 `true`로 마킹 (tombstone). 물리적 삭제는 VACUUM이 담당.
2. **메모리 기반** (`tableFileManager == null`): `ConcurrentHashMap.compute()` 내에서 `filter`로 물리적 삭제.

#### WHERE 조건 처리

| 구성요소 | 파일 | 설명 |
|---------|------|------|
| WhereClause.parse() | `WhereClause.kt:80-134` | WHERE 문자열을 AST로 파싱 |
| WhereEvaluator.matches() | `WhereEvaluator.kt:20-69` | 행 데이터와 조건 매칭 |

**지원하는 조건:**

| 기능 | 예시 | 상태 |
|------|------|------|
| 등호 비교 | `id=1`, `name='Alice'` | 구현 완료 |
| 부등호 비교 | `id != 1`, `name != 'Alice'` | 구현 완료 |
| 대소 비교 | `id > 10`, `age < 30`, `id >= 5`, `age <= 25` | 구현 완료 |
| AND 조건 | `age > 20 AND name='Alice'` | 구현 완료 |
| OR 조건 | `id=1 OR id=2` | 구현 완료 |
| 작은따옴표 | `name='Alice'` | 구현 완료 |
| 큰따옴표 | `name="Alice"` | 구현 완료 |
| 따옴표 없는 숫자 | `id=1` | 구현 완료 |

**타입별 비교 방식:**

| 타입 | 비교 방식 | 파일 위치 |
|------|----------|----------|
| INT | 숫자 비교 (`toIntOrNull()`) | `WhereEvaluator.kt:76-82` |
| VARCHAR | 사전순 문자열 비교 (`compareTo`) | `WhereEvaluator.kt:83-88` |
| BOOLEAN | 불리언 비교 | `WhereEvaluator.kt:89-93` |
| TIMESTAMP | Long 변환 후 숫자 비교 | `WhereEvaluator.kt:94-99` |

#### VACUUM (Tombstone 정리)

| 구성요소 | 파일 | 설명 |
|---------|------|------|
| TableService.vacuum() | `TableService.kt:272-291` | VACUUM 진입점. 메모리 테이블도 갱신 |
| VacuumService.vacuumTable() | `VacuumService.kt` | 재시도 로직 포함 실행 |
| TableFileManager.compactTable() | `TableFileManager.kt:669-790` | tombstone 물리적 제거 (COW 패턴) |
| VacuumScheduler | `VacuumScheduler.kt` | 주기적 자동 VACUUM |
| VacuumLockManager | `VacuumLockManager.kt` | 읽기/쓰기 락 관리 |

#### API 서버

| 구성요소 | 파일 | 설명 |
|---------|------|------|
| REST 엔드포인트 | `TableController.kt:142-166` | `POST /api/tables/delete` |
| TCP 클라이언트 | `DbClient.kt` | 범용 SQL 전송 (DELETE 포함) |

요청 형식:
```json
POST /api/tables/delete
{ "query": "DELETE FROM users WHERE id=1" }
```

#### 프로토콜

- 별도의 DELETE 전용 프로토콜 없음. 범용 문자열 프로토콜(`[4바이트 길이][UTF-8 SQL]`)로 처리.

### 1.2 미구현 영역

#### DELETE 관련 미구현

| 기능 | 설명 | 난이도 |
|------|------|--------|
| **DELETE ... LIMIT** | `DELETE FROM users WHERE age > 20 LIMIT 10` - 삭제 행 수 제한 | 중 |
| **DELETE ... ORDER BY** | `DELETE FROM users ORDER BY id DESC LIMIT 5` - 정렬 후 상위 N개 삭제 | 중 |
| **DELETE ... JOIN** | `DELETE u FROM users u JOIN orders o ON u.id = o.user_id` - 조인 기반 삭제 | 상 |
| **DELETE ... 서브쿼리** | `DELETE FROM users WHERE id IN (SELECT user_id FROM orders)` | 상 |
| **TRUNCATE TABLE** | `TRUNCATE TABLE users` - 전체 행 즉시 삭제 (tombstone 없이) | 하 |

#### SQL 전반 미구현

| 기능 | 설명 | 비고 |
|------|------|------|
| **UPDATE** | `UPDATE users SET name='Bob' WHERE id=1` | Row.update() 메서드는 존재하나 실제 라우팅/실행 미구현 |
| **트랜잭션** | `BEGIN`, `COMMIT`, `ROLLBACK` | CLAUDE.md에 명시적으로 미지원 |
| **WHERE 중첩 괄호** | `WHERE (a=1 OR b=2) AND c=3` | WhereClause 파서가 괄호 미지원 |
| **LIKE 패턴 매칭** | `WHERE name LIKE '%Alice%'` | WhereEvaluator에 LIKE enum은 존재하나 완전 구현 미확인 |
| **NULL 처리** | `WHERE name IS NULL`, `IS NOT NULL` | 전체 시스템이 NULL 미지원 |
| **IN 연산자** | `WHERE id IN (1, 2, 3)` | 미구현 |
| **BETWEEN** | `WHERE age BETWEEN 20 AND 30` | 미구현 |

#### DELETE 파싱의 한계

현재 DELETE 파싱은 `ConnectionHandler.kt`의 Regex로 처리한다:

```
DELETE FROM {tableName} [WHERE {조건}]
```

이 방식의 한계:
- **LIMIT, ORDER BY, JOIN** 등 추가 절을 추출할 수 없다
- **서브쿼리**를 포함한 WHERE 절을 파싱할 수 없다
- JSqlParser로 전환하면 위 기능을 지원할 수 있다 (SELECT는 이미 JSqlParser 사용 중)

### 1.3 DELETE 처리 흐름도

```
Client
  │
  ▼
┌─────────────────────┐
│  API Server         │  POST /api/tables/delete
│  TableController    │  { "query": "DELETE FROM users WHERE id=1" }
└─────────┬───────────┘
          │ TCP
          ▼
┌─────────────────────┐
│  ConnectionHandler  │  Regex 파싱: tableName="users", where="id=1"
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│  TableService       │  WhereClause.parse("id=1")
│  .delete()          │  컬럼 존재 검증
└────┬────────┬───────┘
     │        │
     ▼        ▼
 [파일 기반]  [메모리 기반]
     │             │
     ▼             ▼
┌───────────┐  ConcurrentHashMap
│ TableFile │  .compute() 내
│ Manager   │  rows.filter()로
│ .delete   │  물리적 삭제
│ Rows()    │
│           │
│ tombstone │
│ 마킹       │
└─────┬─────┘
      │
      ▼ (나중에)
┌──────────┐
│ VACUUM   │  tombstone 물리적 제거
│ Service  │  파일 크기 감소
└──────────┘
```

---

## 2. 기능 분할 및 작업 단위 (Feature Segmentation)

### Feature 1: DELETE WHERE 조건 고도화 - **완료**

#### Task 1-1: TableService.delete()에서 WhereEvaluator 연동 - **완료**
- **책임**: 현재 `parseSimpleWhereCondition()`의 단순 등호 파싱을 `WhereClause` + `WhereEvaluator` 기반으로 교체
- **구현 범위**:
  - `TableService.delete()`에서 `whereClause` 문자열을 `WhereClause` 객체로 파싱
  - `WhereEvaluator.matches()`를 사용하여 삭제 대상 판별
  - 메모리 기반 삭제와 파일 기반 삭제 모두에 적용
- **구현 위치**: `TableService.kt:144-187`

#### Task 1-2: TableFileManager.deleteRows()에서 WhereEvaluator 연동 - **완료**
- **책임**: 파일 기반 삭제 시에도 `WhereEvaluator`를 사용하여 조건 평가
- **구현 범위**:
  - `deleteRows()` 메서드에서 `WhereClause` 객체 전달
  - Row 객체에 대해 `WhereEvaluator.matches()` 호출
  - 타입 기반 비교 지원 (INT 숫자 비교, VARCHAR 문자열 비교 등)
- **구현 위치**: `TableFileManager.kt:587-618`

#### Task 1-3: WHERE 조건 파싱 통합 - **완료**
- **책임**: ConnectionHandler의 DELETE 파싱에서 복합 WHERE 조건을 `WhereClause`로 변환
- **구현 범위**:
  - `parseAndHandleDelete()`에서 WHERE 문자열을 `WhereClause`로 파싱하는 로직 추가
  - AND, OR 복합 조건 지원
  - 비교 연산자 (`>`, `<`, `>=`, `<=`, `!=`, `=`) 지원
- **구현 위치**: `ConnectionHandler.kt:431-446`, `WhereClause.kt:80-134`

### Feature 2: DELETE WHERE 조건 검증 - **완료**

#### Task 2-1: WHERE 절 컬럼 존재 검증 - **완료**
- **책임**: WHERE 조건에 사용된 컬럼이 테이블에 존재하는지 검증
- **구현 범위**:
  - `TableService.delete()`에서 WHERE 컬럼 검증 로직
  - 존재하지 않는 컬럼 참조 시 `ColumnNotFoundException` 발생
- **구현 위치**: `TableService.kt:150-153`

### Feature 3: REST API DELETE 엔드포인트 - **완료**

#### Task 3-1: API 서버에 DELETE 엔드포인트 추가 - **완료**
- **책임**: HTTP를 통한 DELETE SQL 실행 지원
- **구현 범위**:
  - `TableController`에 `POST /api/tables/delete` 엔드포인트
  - 요청 본문: `{ "query": "DELETE FROM users WHERE id=1" }`
  - 응답: `{ "success": true, "message": "Deleted 1 row(s)" }`
- **구현 위치**: `TableController.kt:142-166`

### Feature 4: 테스트 보강 - **완료**

#### Task 4-1: 파일 기반 DELETE 테스트 추가 - **완료**
- **책임**: `TableServicePersistenceTest`에 DELETE 테스트 추가
- **테스트 파일**: `TableServicePersistenceTest.kt` (`DeletePersistenceTest` @Nested 클래스)
- **테스트 목록** (8개):
  1. DELETE 후 SELECT 시 삭제된 행이 보이지 않음
  2. DELETE 후 파일에서 직접 읽어도 삭제된 행이 보이지 않음
  3. DELETE 후 파일 내 tombstone 행이 존재함
  4. DELETE 후 재INSERT 시 정상 동작
  5. DELETE 후 서버 재시작 시 삭제 상태 유지
  6. 전체 행 DELETE 후 파일 내 모든 행이 tombstone
  7. 빈 테이블에서 DELETE 시 0 반환
  8. 이미 삭제된 행 재삭제 시 0 반환

#### Task 4-2: 복합 WHERE 조건 DELETE 테스트 추가 - **완료**
- **책임**: 다양한 WHERE 조건에 대한 DELETE 동작 검증
- **테스트 파일**: `TableServiceTest.kt` (`ComplexWhereDeleteTest` @Nested 클래스)
- **테스트 목록** (11개):
  1. AND 조건 DELETE - 두 조건 모두 만족하는 행만 삭제
  2. OR 조건 DELETE - 하나라도 만족하는 행 삭제
  3. 비교 연산자 `>` DELETE
  4. 비교 연산자 `<` DELETE
  5. 비교 연산자 `>=` DELETE
  6. 비교 연산자 `<=` DELETE
  7. 비교 연산자 `!=` DELETE
  8. INT 타입 숫자 비교 DELETE
  9. VARCHAR 타입 문자열 비교 DELETE
  10. 존재하지 않는 컬럼 WHERE 조건 시 예외
  11. AND 조건으로 매칭되는 행이 없을 때 0 반환

#### Task 4-3: DELETE + VACUUM 통합 테스트 - **완료**
- **책임**: DELETE 후 VACUUM 실행 시 tombstone 행이 물리적으로 제거되는지 검증
- **테스트 파일**: `DeleteVacuumIntegrationTest.kt` (신규)
- **테스트 목록** (6개):
  1. DELETE 후 VACUUM 시 tombstone 행이 물리적으로 제거됨
  2. VACUUM 후 파일 크기 감소
  3. VACUUM 후 SELECT 결과 동일
  4. DELETE 전체 행 후 VACUUM 시 빈 테이블
  5. 대량 DELETE 후 VACUUM 통합 시나리오 (1000행)
  6. DELETE + INSERT + VACUUM 혼합 시나리오

---

## 3. 시나리오 문서 (Scenario Documentation)

### 3.1 성공 시나리오

| ID | 시나리오 | 입력 | 기대 결과 | 테스트 상태 |
|----|----------|------|----------|------------|
| S-01 | 단순 등호 조건 DELETE | `DELETE FROM users WHERE id=1` | 해당 행 삭제, 삭제 수 반환 | 검증 완료 |
| S-02 | 문자열 조건 DELETE | `DELETE FROM users WHERE name='Alice'` | 이름이 Alice인 행 삭제 | 검증 완료 |
| S-03 | 전체 행 DELETE | `DELETE FROM users` | 모든 행 삭제 | 검증 완료 |
| S-04 | AND 조건 DELETE | `DELETE FROM users WHERE age > 20 AND status='inactive'` | 두 조건 모두 만족하는 행 삭제 | 검증 완료 |
| S-05 | OR 조건 DELETE | `DELETE FROM users WHERE id=1 OR id=2` | 둘 중 하나라도 만족하는 행 삭제 | 검증 완료 |
| S-06 | 비교 연산자 DELETE | `DELETE FROM users WHERE age >= 30` | age가 30 이상인 행 삭제 | 검증 완료 |
| S-07 | DELETE 후 SELECT | DELETE 후 SELECT | 삭제된 행이 결과에 포함되지 않음 | 검증 완료 |
| S-08 | DELETE 후 VACUUM | DELETE -> VACUUM | tombstone 행 물리적 제거, 파일 크기 감소 | 검증 완료 |
| S-09 | REST API DELETE | `POST /api/tables/delete` | HTTP 200, 삭제 수 반환 | 구현 완료, E2E 미검증 |

### 3.2 엣지 케이스

| ID | 시나리오 | 입력 | 기대 결과 | 테스트 상태 |
|----|----------|------|----------|------------|
| E-01 | 조건에 맞는 행 없음 | `DELETE FROM users WHERE id=999` | 0 행 삭제, 정상 응답 | 검증 완료 |
| E-02 | 빈 테이블에서 DELETE | `DELETE FROM empty_table` | 0 행 삭제, 정상 응답 | 검증 완료 |
| E-03 | 이미 삭제된 행 재삭제 | 같은 조건으로 DELETE 2회 | 두 번째는 0 행 삭제 | 검증 완료 |
| E-04 | VARCHAR 컬럼에 숫자 조건 | `DELETE FROM users WHERE name=123` | 문자열 "123"으로 비교 | 미검증 |
| E-05 | 대소문자 구분 | `DELETE FROM USERS WHERE NAME='Alice'` | 테이블명 대소문자 처리 확인 | 미검증 |
| E-06 | WHERE 값에 공백 포함 | `DELETE FROM users WHERE name='John Doe'` | 공백 포함 문자열 매칭 | 미검증 |

### 3.3 오류 조건

| ID | 시나리오 | 입력 | 기대 결과 | 테스트 상태 |
|----|----------|------|----------|------------|
| F-01 | 존재하지 않는 테이블 | `DELETE FROM nonexistent WHERE id=1` | `IllegalStateException` | 검증 완료 |
| F-02 | 잘못된 SQL 구문 | `DELETE users WHERE` | `errorCode: 400` 응답 | 미검증 |
| F-03 | 잘못된 WHERE 구문 | `DELETE FROM users WHERE invalid` | `IllegalArgumentException` | 검증 완료 |
| F-04 | 존재하지 않는 컬럼 | `DELETE FROM users WHERE email='x'` | `ColumnNotFoundException` | 검증 완료 |
| F-05 | 타입 불일치 비교 | `DELETE FROM users WHERE id > 'abc'` | 적절한 에러 또는 타입 변환 | 미검증 |

### 3.4 경계 테스트

| ID | 시나리오 | 입력 | 기대 결과 | 테스트 상태 |
|----|----------|------|----------|------------|
| B-01 | INT 최대값 조건 | `WHERE id = 2147483647` | 정상 매칭 | 미검증 |
| B-02 | 빈 문자열 값 | `WHERE name = ''` | 빈 문자열과 매칭 | 미검증 |
| B-03 | 대량 행 DELETE (1000+) | 1000행 중 500행 DELETE | 500행 삭제, 500행 유지 | 검증 완료 |
| B-04 | 모든 행이 이미 삭제됨 | tombstone만 있는 테이블에 DELETE | 0 행 삭제 | 검증 완료 |
| B-05 | DELETE 직후 INSERT | DELETE -> INSERT 동일 값 | 새 행 정상 삽입 | 검증 완료 |

---

## 4. 테스트 현황 요약

### 4.1 테스트 커버리지

| 테스트 파일 | 테스트 그룹 | 테스트 수 | 커버리지 |
|------------|-----------|----------|---------|
| `TableServiceTest.kt` | DeleteTest | 8개 | 기본 WHERE, 전체 삭제, 카운트, 예외, 따옴표 |
| `TableServiceTest.kt` | ComplexWhereDeleteTest | 11개 | AND/OR, 비교 연산자 6종, INT/VARCHAR 타입 비교, 컬럼 미존재 예외 |
| `TableServicePersistenceTest.kt` | DeletePersistenceTest | 8개 | SELECT 필터링, 파일 직접 읽기, tombstone 검증, 재INSERT, 서버 재시작, 전체 삭제, 빈 테이블, 재삭제 |
| `DeleteVacuumIntegrationTest.kt` | (루트) | 6개 | tombstone 물리적 제거, 파일 크기 감소, SELECT 결과 동일성, 전체 DELETE+VACUUM, 대량(1000행), DELETE+INSERT+VACUUM 혼합 |
| **합계** | | **33개** | |

### 4.2 기능 영역별 테스트 분류

#### SQL 파싱 영역
- 테스트 파일: `ConnectionHandlerTest.kt`
- 대상: DELETE SQL 파싱 및 라우팅
- 시나리오: S-01~S-06, F-02, F-03

#### 비즈니스 로직 영역
- 테스트 파일: `TableServiceTest.kt`
- 대상: DELETE 조건 평가, 메모리 기반 삭제
- 시나리오: S-01~S-07, E-01~E-06, F-01, F-04

#### 스토리지 영역
- 테스트 파일: `TableServicePersistenceTest.kt`
- 대상: 파일 기반 tombstone 마킹, 디스크 I/O
- 시나리오: S-07, S-08, B-03, B-04

#### VACUUM 영역
- 테스트 파일: `DeleteVacuumIntegrationTest.kt`
- 대상: DELETE 후 VACUUM 연동
- 시나리오: S-08

#### REST API 영역
- 테스트 파일: (신규) `TableControllerTest.kt` 또는 기존 테스트 확장
- 대상: HTTP DELETE 엔드포인트
- 시나리오: S-09

---

## 5. 작업 완료 현황

```
Feature 1: DELETE WHERE 조건 고도화              ██████████ 완료
  Task 1-1: WhereEvaluator 연동 (TableService)    ██████████ 완료
  Task 1-2: WhereEvaluator 연동 (TableFileManager) ██████████ 완료
  Task 1-3: 복합 WHERE 조건 파싱 (ConnectionHandler) ██████████ 완료

Feature 2: DELETE WHERE 조건 검증                ██████████ 완료
  Task 2-1: WHERE 컬럼 존재 검증                   ██████████ 완료

Feature 3: REST API DELETE 엔드포인트            ██████████ 완료
  Task 3-1: REST API DELETE 엔드포인트 추가        ██████████ 완료

Feature 4: 테스트 보강                           ██████████ 완료
  Task 4-1: 파일 기반 DELETE 테스트 (8개)          ██████████ 완료
  Task 4-2: 복합 WHERE DELETE 테스트 (11개)        ██████████ 완료
  Task 4-3: DELETE + VACUUM 통합 테스트 (6개)      ██████████ 완료
```

---

## 6. 향후 개선 후보 (Future Work)

### DELETE 기능 확장

| 우선순위 | 기능 | 설명 | 난이도 |
|---------|------|------|--------|
| 높음 | DELETE ... LIMIT | 삭제 행 수 제한 | 중 |
| 높음 | TRUNCATE TABLE | 전체 행 즉시 삭제 (tombstone 없이) | 하 |
| 중간 | DELETE ... ORDER BY | 정렬 후 상위 N개 삭제 | 중 |
| 중간 | WHERE 중첩 괄호 | `(a=1 OR b=2) AND c=3` 지원 | 중 |
| 중간 | IN 연산자 | `WHERE id IN (1, 2, 3)` | 중 |
| 중간 | BETWEEN | `WHERE age BETWEEN 20 AND 30` | 중 |
| 낮음 | DELETE ... JOIN | 조인 기반 삭제 | 상 |
| 낮음 | DELETE ... 서브쿼리 | `WHERE id IN (SELECT ...)` | 상 |
| 낮음 | LIKE 패턴 매칭 | `WHERE name LIKE '%Alice%'` | 중 |
| 낮음 | NULL 처리 | `IS NULL`, `IS NOT NULL` | 중 |

### SQL 전반 확장

| 우선순위 | 기능 | 설명 |
|---------|------|------|
| 높음 | UPDATE | `UPDATE users SET name='Bob' WHERE id=1` |
| 낮음 | 트랜잭션 | `BEGIN`, `COMMIT`, `ROLLBACK` |
| 낮음 | EXPLAIN DELETE | DELETE 실행 계획 분석 |

### 성능/아키텍처 개선

| 우선순위 | 기능 | 설명 |
|---------|------|------|
| 중간 | JSqlParser로 DELETE 파싱 전환 | LIMIT, ORDER BY, JOIN 등 고급 구문 지원 가능 |
| 낮음 | Incremental VACUUM | 페이지 단위 점진적 정리 |
| 낮음 | In-Place Delete | 파일 전체 재작성 대신 해당 페이지만 수정 (MySQL Purge 방식) |

---

## 7. MYSQL_PURGE_VS_VACUUM.md 참고 개선 방향

현재 프로젝트는 MYSQL_PURGE_VS_VACUUM.md에서 분석한 바와 같이 **Copy-on-Write 기반의 VACUUM 패턴**을 사용하고 있다.

### 현재 유지 중인 설계 결정
1. **Tombstone 방식의 논리적 삭제** - 현재 Row.deleted 플래그 기반, MySQL의 deleted_flag와 동일한 개념
2. **Copy-on-Write 파일 쓰기** - DELETE 시 전체 파일 재작성, 단순하지만 안전
3. **수동 VACUUM** - 명시적 공간 회수, 교육 목적에 적합

### 완료된 개선
1. **WHERE 조건 평가 고도화** - `WhereEvaluator`를 DELETE에 연동하여 AND/OR, 비교 연산자 지원
2. **타입 기반 비교** - `WhereEvaluator`의 `compareValues()`를 활용하여 INT는 숫자 비교, VARCHAR는 문자열 비교 지원
