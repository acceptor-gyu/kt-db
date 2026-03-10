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

## 1. 현재 구현 분석 (PROBLEM Analysis)

### 구현 완료된 부분

| 구성 요소 | 파일 | 상태 | 설명 |
|-----------|------|------|------|
| Row 모델 | `common/.../Row.kt` | 완료 | `deleted` 플래그, `markAsDeleted()`, `isActive()` |
| RowEncoder | `storage/RowEncoder.kt` | 완료 | deleted flag + version 인코딩/디코딩 (1 byte + 8 bytes) |
| SQL 파싱 (DELETE) | `ConnectionHandler.kt` | 완료 | `DELETE FROM table WHERE col=val` 파싱 |
| TableService.delete() | `service/TableService.kt` | 완료 | 메모리 기반 + 파일 기반 삭제 분기 |
| TableFileManager.deleteRows() | `storage/TableFileManager.kt` | 완료 | Tombstone 마킹 + Copy-on-Write 파일 쓰기 |
| readTable (필터링) | `storage/TableFileManager.kt` | 완료 | `decodePageToRows()`에서 deleted 행 필터링 |
| VACUUM | `vacuum/VacuumService.kt` | 완료 | Copy-on-Write 압축, 재시도, 스케줄링 |
| DELETE 테스트 | `TableServiceTest.kt` | 완료 | 7개 DELETE 테스트 (메모리 기반) |

### 구현되지 않은/개선 필요한 부분

| 항목 | 현재 상태 | 필요한 개선 |
|------|----------|------------|
| **복합 WHERE 조건** | `col=val` 단순 등호만 지원 | `>`, `<`, `>=`, `<=`, `!=`, AND, OR 지원 |
| **WhereEvaluator 연동** | `common/where/WhereEvaluator` 존재하지만 DELETE에 미사용 | DELETE에서 WhereEvaluator 활용 |
| **타입 기반 비교** | 문자열 비교만 사용 (`row.data[col] == val`) | INT는 숫자, TIMESTAMP는 시간 비교 |
| **REST API DELETE 엔드포인트** | 미구현 (DROP TABLE만 존재) | `DELETE /api/tables/delete` 엔드포인트 추가 |
| **파일 기반 DELETE 테스트** | 미구현 | `TableServicePersistenceTest`에 DELETE 케이스 추가 |
| **DELETE + VACUUM 통합 테스트** | 미구현 | DELETE 후 VACUUM 실행 검증 |
| **DELETE 시 Buffer Pool 무효화** | `deleteRows()` 내부에서 `writeTableWithRows()` 호출 시 처리됨 | 검증 테스트 추가 |
| **WHERE 컬럼 존재 검증** | 미구현 | 존재하지 않는 컬럼 참조 시 에러 |
| **DELETE 실행 계획 (EXPLAIN DELETE)** | 미구현 | 후순위로 고려 |

---

## 2. 기능 분할 및 작업 단위 (Feature Segmentation)

### Feature 1: DELETE WHERE 조건 고도화

#### Task 1-1: TableService.delete()에서 WhereEvaluator 연동
- **책임**: 현재 `parseSimpleWhereCondition()`의 단순 등호 파싱을 `WhereClause` + `WhereEvaluator` 기반으로 교체
- **구현 범위**:
  - `TableService.delete()`에서 `whereClause` 문자열을 `WhereClause` 객체로 파싱
  - `WhereEvaluator.matches()`를 사용하여 삭제 대상 판별
  - 메모리 기반 삭제와 파일 기반 삭제 모두에 적용
- **커밋 단위**: `DELETE에 WhereEvaluator 연동`
- **예상 시간**: 1.5시간

#### Task 1-2: TableFileManager.deleteRows()에서 WhereEvaluator 연동
- **책임**: 파일 기반 삭제 시에도 `WhereEvaluator`를 사용하여 조건 평가
- **구현 범위**:
  - `deleteRows()` 메서드 시그니처 변경: `(tableName, schema, whereClause)` -> `WhereClause` 객체 전달
  - Row 객체에 대해 `WhereEvaluator.matches()` 호출
  - 타입 기반 비교 지원 (INT 숫자 비교, VARCHAR 문자열 비교 등)
- **커밋 단위**: `TableFileManager DELETE에 WhereEvaluator 적용`
- **예상 시간**: 1시간

#### Task 1-3: WHERE 조건 파싱 통합
- **책임**: ConnectionHandler의 DELETE 파싱에서 복합 WHERE 조건을 `WhereClause`로 변환
- **구현 범위**:
  - `parseAndHandleDelete()`에서 WHERE 문자열을 `WhereClause`로 파싱하는 로직 추가
  - AND, OR 복합 조건 지원
  - 비교 연산자 (`>`, `<`, `>=`, `<=`, `!=`, `=`) 지원
- **커밋 단위**: `DELETE 복합 WHERE 조건 파싱 지원`
- **예상 시간**: 2시간

### Feature 2: DELETE WHERE 조건 검증

#### Task 2-1: WHERE 절 컬럼 존재 검증
- **책임**: WHERE 조건에 사용된 컬럼이 테이블에 존재하는지 검증
- **구현 범위**:
  - `TableService.delete()` 또는 `Resolver`에서 WHERE 컬럼 검증 로직 추가
  - 존재하지 않는 컬럼 참조 시 `ColumnNotFoundException` 발생
- **커밋 단위**: `DELETE WHERE 컬럼 존재 검증 추가`
- **예상 시간**: 1시간

### Feature 3: REST API DELETE 엔드포인트

#### Task 3-1: API 서버에 DELETE 엔드포인트 추가
- **책임**: HTTP를 통한 DELETE SQL 실행 지원
- **구현 범위**:
  - `TableController`에 `DELETE /api/tables/delete` 엔드포인트 추가
  - 요청 본문: `{ "query": "DELETE FROM users WHERE id=1" }`
  - 응답: `{ "success": true, "message": "Deleted 1 row(s)" }`
- **커밋 단위**: `REST API DELETE 엔드포인트 추가`
- **예상 시간**: 0.5시간

### Feature 4: 테스트 보강

#### Task 4-1: 파일 기반 DELETE 테스트 추가
- **책임**: `TableServicePersistenceTest` 또는 `TableFileManagerTest`에 DELETE 테스트 추가
- **구현 범위**:
  - DELETE 후 파일에서 읽었을 때 삭제된 행이 보이지 않는지 검증
  - DELETE 후 파일 내 tombstone 행이 존재하는지 검증
  - DELETE + 재INSERT 시나리오
- **커밋 단위**: `파일 기반 DELETE 유닛 테스트 추가`
- **예상 시간**: 1.5시간

#### Task 4-2: 복합 WHERE 조건 DELETE 테스트 추가
- **책임**: 다양한 WHERE 조건에 대한 DELETE 동작 검증
- **구현 범위**:
  - `AND` 조건: `DELETE FROM users WHERE age > 20 AND status = 'inactive'`
  - `OR` 조건: `DELETE FROM users WHERE id = 1 OR id = 2`
  - 비교 연산자: `>`, `<`, `>=`, `<=`, `!=`
  - 타입 기반 비교 (INT vs VARCHAR)
- **커밋 단위**: `복합 WHERE 조건 DELETE 테스트 추가`
- **예상 시간**: 1.5시간

#### Task 4-3: DELETE + VACUUM 통합 테스트
- **책임**: DELETE 후 VACUUM 실행 시 tombstone 행이 물리적으로 제거되는지 검증
- **구현 범위**:
  - DELETE -> getTableStatistics -> VACUUM -> getTableStatistics 전체 흐름 검증
  - VACUUM 후 파일 크기 감소 확인
  - VACUUM 후 SELECT 결과 검증
- **커밋 단위**: `DELETE + VACUUM 통합 테스트 추가`
- **예상 시간**: 1.5시간

---

## 3. 시나리오 문서 (Scenario Documentation)

### 3.1 성공 시나리오

| ID | 시나리오 | 입력 | 기대 결과 |
|----|----------|------|----------|
| S-01 | 단순 등호 조건 DELETE | `DELETE FROM users WHERE id=1` | 해당 행 삭제, 삭제 수 반환 |
| S-02 | 문자열 조건 DELETE | `DELETE FROM users WHERE name='Alice'` | 이름이 Alice인 행 삭제 |
| S-03 | 전체 행 DELETE | `DELETE FROM users` | 모든 행 삭제 |
| S-04 | AND 조건 DELETE | `DELETE FROM users WHERE age > 20 AND status='inactive'` | 두 조건 모두 만족하는 행 삭제 |
| S-05 | OR 조건 DELETE | `DELETE FROM users WHERE id=1 OR id=2` | 둘 중 하나라도 만족하는 행 삭제 |
| S-06 | 비교 연산자 DELETE | `DELETE FROM users WHERE age >= 30` | age가 30 이상인 행 삭제 |
| S-07 | DELETE 후 SELECT | DELETE 후 SELECT | 삭제된 행이 결과에 포함되지 않음 |
| S-08 | DELETE 후 VACUUM | DELETE -> VACUUM | tombstone 행 물리적 제거, 파일 크기 감소 |
| S-09 | REST API DELETE | `POST /api/tables/delete` | HTTP 200, 삭제 수 반환 |

### 3.2 엣지 케이스

| ID | 시나리오 | 입력 | 기대 결과 |
|----|----------|------|----------|
| E-01 | 조건에 맞는 행 없음 | `DELETE FROM users WHERE id=999` | 0 행 삭제, 정상 응답 |
| E-02 | 빈 테이블에서 DELETE | `DELETE FROM empty_table` | 0 행 삭제, 정상 응답 |
| E-03 | 이미 삭제된 행 재삭제 | 같은 조건으로 DELETE 2회 | 두 번째는 0 행 삭제 |
| E-04 | VARCHAR 컬럼에 숫자 조건 | `DELETE FROM users WHERE name=123` | 문자열 "123"으로 비교 |
| E-05 | 대소문자 구분 | `DELETE FROM USERS WHERE NAME='Alice'` | 테이블명 대소문자 처리 확인 |
| E-06 | WHERE 값에 공백 포함 | `DELETE FROM users WHERE name='John Doe'` | 공백 포함 문자열 매칭 |

### 3.3 오류 조건

| ID | 시나리오 | 입력 | 기대 결과 |
|----|----------|------|----------|
| F-01 | 존재하지 않는 테이블 | `DELETE FROM nonexistent WHERE id=1` | `IllegalStateException` |
| F-02 | 잘못된 SQL 구문 | `DELETE users WHERE` | `errorCode: 400` 응답 |
| F-03 | 잘못된 WHERE 구문 | `DELETE FROM users WHERE invalid` | `IllegalArgumentException` |
| F-04 | 존재하지 않는 컬럼 (개선 대상) | `DELETE FROM users WHERE email='x'` | `ColumnNotFoundException` |
| F-05 | 타입 불일치 비교 | `DELETE FROM users WHERE id > 'abc'` | 적절한 에러 또는 타입 변환 |

### 3.4 경계 테스트

| ID | 시나리오 | 입력 | 기대 결과 |
|----|----------|------|----------|
| B-01 | INT 최대값 조건 | `WHERE id = 2147483647` | 정상 매칭 |
| B-02 | 빈 문자열 값 | `WHERE name = ''` | 빈 문자열과 매칭 |
| B-03 | 대량 행 DELETE (1000+) | 1000행 중 500행 DELETE | 500행 삭제, 500행 유지 |
| B-04 | 모든 행이 이미 삭제됨 | tombstone만 있는 테이블에 DELETE | 0 행 삭제 |
| B-05 | DELETE 직후 INSERT | DELETE -> INSERT 동일 값 | 새 행 정상 삽입 |

---

## 4. 테스트 전략 (Test Strategy Development)

### 4.1 우선순위별 시나리오 분류

#### Critical (반드시 통과해야 함)
- S-01: 단순 등호 조건 DELETE
- S-03: 전체 행 DELETE
- S-07: DELETE 후 SELECT
- F-01: 존재하지 않는 테이블 에러
- F-02: 잘못된 SQL 구문 에러

#### High (핵심 기능)
- S-02: 문자열 조건 DELETE
- S-04: AND 조건 DELETE
- S-06: 비교 연산자 DELETE
- S-08: DELETE 후 VACUUM
- E-01: 조건에 맞는 행 없음
- E-03: 이미 삭제된 행 재삭제
- F-04: 존재하지 않는 컬럼 에러

#### Medium (중요하지만 후순위)
- S-05: OR 조건 DELETE
- S-09: REST API DELETE
- E-02: 빈 테이블에서 DELETE
- E-06: WHERE 값에 공백 포함
- B-03: 대량 행 DELETE
- B-05: DELETE 직후 INSERT

#### Low (엣지 케이스)
- E-04: VARCHAR 컬럼에 숫자 조건
- E-05: 대소문자 구분
- B-01: INT 최대값 조건
- B-02: 빈 문자열 값
- B-04: 모든 행이 이미 삭제됨
- F-05: 타입 불일치 비교

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
- 테스트 파일: `TableFileManagerTest.kt`, `TableServicePersistenceTest.kt`
- 대상: 파일 기반 tombstone 마킹, 디스크 I/O
- 시나리오: S-07, S-08, B-03, B-04

#### VACUUM 영역
- 테스트 파일: `VacuumServiceTest.kt`, `VacuumIntegrationTest.kt`
- 대상: DELETE 후 VACUUM 연동
- 시나리오: S-08

#### REST API 영역
- 테스트 파일: (신규) `TableControllerTest.kt` 또는 기존 테스트 확장
- 대상: HTTP DELETE 엔드포인트
- 시나리오: S-09

---

## 5. 작업 순서 및 커밋 전략

```
Task 1-1: DELETE에 WhereEvaluator 연동 (TableService)
  └─> Task 1-2: TableFileManager DELETE에 WhereEvaluator 적용
       └─> Task 1-3: DELETE 복합 WHERE 조건 파싱 지원 (ConnectionHandler)
            └─> Task 2-1: DELETE WHERE 컬럼 존재 검증 추가
                 └─> Task 3-1: REST API DELETE 엔드포인트 추가

Task 4-1: 파일 기반 DELETE 유닛 테스트 추가        (Task 1-2 이후)
Task 4-2: 복합 WHERE 조건 DELETE 테스트 추가       (Task 1-3 이후)
Task 4-3: DELETE + VACUUM 통합 테스트 추가          (Task 1-2 이후)
```

### 총 예상 시간: ~11시간

| Task | 예상 시간 | 의존성 |
|------|----------|--------|
| 1-1: WhereEvaluator 연동 (TableService) | 1.5h | 없음 |
| 1-2: WhereEvaluator 연동 (TableFileManager) | 1.0h | Task 1-1 |
| 1-3: 복합 WHERE 파싱 (ConnectionHandler) | 2.0h | Task 1-2 |
| 2-1: WHERE 컬럼 존재 검증 | 1.0h | Task 1-3 |
| 3-1: REST API DELETE 엔드포인트 | 0.5h | Task 1-3 |
| 4-1: 파일 기반 DELETE 테스트 | 1.5h | Task 1-2 |
| 4-2: 복합 WHERE DELETE 테스트 | 1.5h | Task 1-3 |
| 4-3: DELETE + VACUUM 통합 테스트 | 1.5h | Task 1-2 |

---

## 6. MYSQL_PURGE_VS_VACUUM.md 참고 개선 방향

현재 프로젝트는 MYSQL_PURGE_VS_VACUUM.md에서 분석한 바와 같이 **Copy-on-Write 기반의 VACUUM 패턴**을 사용하고 있다. DELETE record 개선 시 다음을 참고한다:

### 현재 유지할 설계 결정
1. **Tombstone 방식의 논리적 삭제** - 현재 Row.deleted 플래그 기반, MySQL의 deleted_flag와 동일한 개념
2. **Copy-on-Write 파일 쓰기** - DELETE 시 전체 파일 재작성, 단순하지만 안전
3. **수동 VACUUM** - 명시적 공간 회수, 교육 목적에 적합

### 이번 작업에서 개선할 부분
1. **WHERE 조건 평가 고도화** - 이미 구현된 `WhereEvaluator`를 DELETE에 연동하여 단순 등호 비교에서 벗어남
2. **타입 기반 비교** - `WhereEvaluator`의 `compareValues()`를 활용하여 INT는 숫자 비교, VARCHAR는 문자열 비교 지원

### 향후 개선 후보 (이번 작업 범위 밖)
1. **Incremental VACUUM** - 페이지 단위 점진적 정리 (MYSQL_PURGE_VS_VACUUM.md 6.1절 참고)
2. **Background VACUUM** - 자동 VACUUM 스케줄러 (이미 VacuumScheduler 구현됨, 추가 최적화 가능)
3. **In-Place Delete** - 파일 전체 재작성 대신 해당 페이지만 수정 (MySQL Purge 방식)
4. **DELETE 실행 계획** - EXPLAIN DELETE 지원
