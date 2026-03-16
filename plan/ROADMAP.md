# 구현 현황 및 로드맵

## 구현 완료된 기능

### SQL 명령어
| 명령어 | 형식 | 비고 |
|--------|------|------|
| CREATE TABLE | `CREATE TABLE name (col type, ...)` | INT, VARCHAR, BOOLEAN, TIMESTAMP 지원 |
| INSERT | `INSERT INTO table VALUES (col="val", ...)` | 단일 행 삽입 |
| SELECT | `SELECT * FROM table` | 전체 테이블 스캔만 지원 |
| DELETE | `DELETE FROM table [WHERE ...]` | WHERE 절 지원 (AND/OR) |
| DROP TABLE | `DROP TABLE name` | 메모리 + 디스크 삭제 |
| EXPLAIN | `EXPLAIN SELECT ...` | Elasticsearch 기반 쿼리 플랜 분석 |
| VACUUM | `VACUUM tablename` | 삭제된 행(tombstone) 물리적 제거 |
| PING | `PING` | 연결 상태 확인 |

### WHERE 절 (DELETE에서만 동작)
- 비교 연산자: `=`, `!=`, `>`, `>=`, `<`, `<=`
- 범위: `BETWEEN`
- 패턴: `LIKE`
- NULL 검사: `IS NULL`, `IS NOT NULL`
- 논리 연산자: `AND`, `OR`

### 스토리지
- 바이너리 파일 영속성 (`./data/*.dat`)
- Buffer Pool (16KB 페이지, LRU 캐시)
- Atomic Write (temp → sync → rename)
- 서버 재시작 시 자동 복구

### 동시성
- ConcurrentHashMap 기반 thread-safe 테이블 관리
- AtomicLong 기반 Connection ID 생성
- 멀티 클라이언트 TCP 연결

### 모니터링
- Elasticsearch 쿼리 로깅
- Kibana 시각화
- EXPLAIN 실행 계획 분석

---

## 미구현 기능 구현 로드맵

### Phase 1: SELECT 강화
1. **SELECT WHERE** — SELECT에서 WHERE 절 적용 (파서는 이미 존재, 실행만 연결)
   - MySQL: 옵티마이저가 WHERE 조건을 분석하여 Full Table Scan 또는 Index Scan 중 선택. 조건 평가 순서를 비용 기반으로 재배치(Condition Pushdown)
2. **SELECT 특정 컬럼** — `SELECT col1, col2 FROM table` (현재 `*`만 가능)
   - MySQL: 프로젝션(Projection) 연산으로 필요한 컬럼만 추출. Covering Index가 있으면 테이블 접근 없이 인덱스에서 직접 반환
3. **ORDER BY** — 결과 정렬
   - MySQL: 메모리 내 Filesort(Quick Sort) 사용. `sort_buffer_size` 초과 시 디스크 임시 파일로 External Merge Sort 수행. 인덱스 순서와 일치하면 정렬 생략
4. **LIMIT / OFFSET** — 결과 페이지네이션
   - MySQL: 실행 엔진에서 N개 행 반환 후 즉시 중단(Early Termination). OFFSET이 크면 앞의 행을 모두 스캔 후 버리므로 Keyset Pagination이 더 효율적

### Phase 2: DML 확장
5. **UPDATE** — `UPDATE table SET col=val WHERE ...`
   - MySQL(InnoDB): MVCC 기반으로 동작. 기존 행을 Undo Log에 저장 → 원본 페이지를 직접 수정(in-place update) → Redo Log에 변경 기록. 인덱스 컬럼 변경 시 Delete-Mark + Insert 방식
6. **INSERT 다중 행** — `INSERT INTO table VALUES (...), (...)`
   - MySQL: Bulk Insert 최적화. 여러 행을 하나의 트랜잭션으로 묶어 Redo Log 쓰기 횟수 감소. Auto-Increment 값을 배치 단위로 미리 할당

### Phase 3: DDL 확장
7. **ALTER TABLE** — 컬럼 추가/삭제/타입 변경
   - MySQL: Online DDL 지원. ALGORITHM=INPLACE(테이블 재구성 없이 메타데이터만 변경)와 ALGORITHM=COPY(새 테이블 생성 후 데이터 복사) 두 전략. 컬럼 추가는 Instant DDL로 메타데이터만 변경 가능(8.0+)
8. **SHOW TABLES** — 테이블 목록 조회
   - MySQL: `information_schema.TABLES` 시스템 테이블에서 조회. 데이터 딕셔너리(DD)를 InnoDB 시스템 테이블스페이스에 저장(8.0+)
9. **DESCRIBE** — 테이블 스키마 조회
   - MySQL: `information_schema.COLUMNS`에서 컬럼명, 타입, NULL 여부, 키 정보, 기본값 등을 조회

### Phase 4: 제약 조건
10. **PRIMARY KEY** — 기본 키 지정 및 중복 방지
    - MySQL(InnoDB): PK가 곧 Clustered Index. 테이블 데이터가 PK 순서로 물리적 정렬되어 저장됨. PK 없으면 내부적으로 6바이트 Row ID 자동 생성
11. **NOT NULL** — NULL 값 불허 제약
    - MySQL: INSERT/UPDATE 시 컬럼별 NULL 검사. Strict Mode에서는 즉시 에러, 비엄격 모드에서는 타입별 기본값(0, '', CURRENT_TIMESTAMP 등)으로 대체
12. **UNIQUE** — 유니크 제약 조건
    - MySQL: Unique Index를 생성하여 B-Tree에서 중복 키 삽입 시 Duplicate Key Error 반환. NULL은 여러 개 허용(NULL != NULL)
13. **DEFAULT** — 기본 값 설정
    - MySQL: 메타데이터에 기본값 저장. INSERT 시 누락된 컬럼에 기본값 자동 적용. 8.0+에서는 표현식 기본값 지원 `(UUID()`, `CURRENT_TIMESTAMP` 등)
14. **AUTO INCREMENT** — 자동 증가 값
    - MySQL(InnoDB): 메모리에 카운터 유지, 서버 재시작 시 `SELECT MAX(id)+1`로 복구(5.7) 또는 Redo Log에서 복구(8.0+). 갭 발생 가능(롤백/삭제 시 값 재사용 안 함)

### Phase 5: 인덱스
15. **CREATE INDEX** — B-Tree 인덱스 생성
    - MySQL(InnoDB): B+Tree 구조. 리프 노드가 Linked List로 연결되어 Range Scan에 유리. Secondary Index의 리프에는 PK 값을 저장하여 실제 행은 Clustered Index에서 조회(Double Lookup)
16. **인덱스 기반 검색** — WHERE 절에서 인덱스 활용
    - MySQL: 옵티마이저가 카디널리티 통계와 비용 모델로 인덱스 사용 여부 결정. Index Condition Pushdown(ICP)으로 스토리지 엔진 레벨에서 조건 필터링. 인덱스 힌트(`USE INDEX`, `FORCE INDEX`)로 수동 제어 가능
17. **DROP INDEX** — 인덱스 삭제
    - MySQL: Online DDL로 메타데이터 제거 후 백그라운드에서 인덱스 페이지 정리

### Phase 6: 집계 & 그룹
18. **집계 함수** — COUNT, SUM, AVG, MIN, MAX
    - MySQL: 행을 순회하며 누적 계산. `COUNT(*)`는 InnoDB에서 가장 작은 Secondary Index를 스캔(Clustered Index보다 작으므로). `MIN/MAX`는 B-Tree 인덱스의 첫/마지막 엔트리로 O(1) 조회 가능
19. **GROUP BY** — 그룹별 집계
    - MySQL: 인덱스 순서와 GROUP BY 순서가 일치하면 Streaming Aggregation(정렬 불필요). 불일치 시 임시 테이블 생성 후 해시 또는 정렬 기반 그룹핑
20. **HAVING** — 집계 결과 필터링
    - MySQL: GROUP BY 후 집계 결과에 조건 적용. WHERE는 그룹핑 전 필터, HAVING은 그룹핑 후 필터
21. **DISTINCT** — 중복 제거
    - MySQL: 내부적으로 GROUP BY와 동일하게 처리. 임시 테이블에 Unique Hash Index를 만들어 중복 판별

### Phase 7: 조인
22. **INNER JOIN** — 내부 조인
    - MySQL: Nested Loop Join(NLJ) 기본 전략. 외부 테이블 행마다 내부 테이블을 인덱스로 탐색. 8.0.18+에서 Hash Join 지원(인덱스 없는 등가 조인 시). Join Buffer로 Block Nested Loop 최적화
23. **LEFT/RIGHT JOIN** — 외부 조인
    - MySQL: NLJ에서 매칭 행이 없으면 NULL로 채워서 반환. 옵티마이저가 가능하면 INNER JOIN으로 변환(WHERE 조건이 NULL을 제거하는 경우)
24. **서브쿼리** — `SELECT * FROM (SELECT ...) AS t`
    - MySQL: Derived Table은 임시 테이블로 Materialization하거나, 외부 쿼리에 Merge하여 처리. EXISTS는 Semi-Join 최적화(FirstMatch, Duplicate Weedout 등)로 변환

### Phase 8: 트랜잭션
25. **BEGIN / COMMIT / ROLLBACK** — 기본 트랜잭션
    - MySQL(InnoDB): Undo Log로 롤백 지원. COMMIT 시 Redo Log를 디스크에 fsync(`innodb_flush_log_at_trx_commit` 설정). Group Commit으로 여러 트랜잭션의 fsync를 묶어 I/O 최적화
26. **WAL (Write-Ahead Log)** — 트랜잭션 내구성 보장
    - MySQL(InnoDB): Redo Log(WAL)에 변경 사항을 먼저 기록 후 데이터 페이지는 나중에 반영(Write-Ahead). 체크포인트 시 Dirty Page를 디스크에 플러시. 크래시 복구 시 Redo Log를 재실행(Redo)하고 미완료 트랜잭션은 Undo Log로 롤백
27. **격리 수준** — READ COMMITTED, REPEATABLE READ
    - MySQL(InnoDB): MVCC(Multi-Version Concurrency Control)로 구현. 각 행에 트랜잭션 ID와 롤백 포인터 저장. REPEATABLE READ는 트랜잭션 시작 시점의 스냅샷을 읽고, READ COMMITTED는 각 쿼리 시점의 최신 커밋 데이터를 읽음. Gap Lock으로 Phantom Read 방지(RR에서만)

### Phase 9: 고급 기능
28. **FOREIGN KEY** — 외래 키 및 참조 무결성
    - MySQL(InnoDB): 자식 테이블 INSERT/UPDATE 시 부모 테이블에 공유 잠금(S-Lock)을 걸어 참조 무결성 검증. CASCADE/SET NULL/RESTRICT 등 참조 액션 지원. 외래 키 컬럼에 자동으로 인덱스 생성
29. **VIEW** — 가상 테이블
    - MySQL: MERGE 알고리즘(뷰 정의를 외부 쿼리에 인라인) 또는 TEMPTABLE 알고리즘(뷰 결과를 임시 테이블로 Materialization). MERGE가 가능하면 성능상 유리
30. **IN 연산자** — `WHERE col IN (1, 2, 3)`
    - MySQL: 값 목록을 정렬 후 이진 탐색으로 매칭 검사. 인덱스가 있으면 각 값에 대해 Index Lookup 수행. 서브쿼리 IN은 Semi-Join 또는 Materialization으로 최적화
31. **UNION** — 결과 집합 합치기
    - MySQL: 각 SELECT를 독립 실행 후 결과를 임시 테이블에 합침. UNION은 Unique Index로 중복 제거, UNION ALL은 중복 제거 없이 단순 결합(더 빠름)
