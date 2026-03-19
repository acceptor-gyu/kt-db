# db-server/src

TCP 기반 데이터베이스 서버 모듈. 파일 기반 영속성, 버퍼 풀, SQL 파싱, EXPLAIN 등 핵심 DB 엔진을 구현한다.

## 구조

```
main/kotlin/study/db/server/
├── DbServerApplication.kt              # Spring Boot 진입점
├── DbTcpServer.kt                      # TCP 서버 (포트 9000)
├── service/
│   └── TableService.kt                 # 테이블 CRUD (ConcurrentHashMap, 스레드 안전)
├── db_engine/
│   ├── ConnectionHandler.kt            # 커넥션별 SQL 명령 처리
│   ├── ConnectionManager.kt            # 커넥션 관리 (AtomicLong ID)
│   ├── ConnectionState.kt              # 커넥션 상태
│   ├── Resolver.kt                     # SQL 명령 라우팅
│   ├── SqlParser.kt                    # SQL 파싱 (CREATE, INSERT, SELECT, DELETE 등)
│   └── dto/
│       ├── ParsedQuery.kt              # 파싱된 쿼리 DTO (whereString, orderByColumns, limit, offset 포함)
│       ├── OrderByColumn.kt            # ORDER BY 컬럼 DTO (columnName, ascending)
│       └── WhereCondition.kt           # WHERE 조건 DTO
├── storage/
│   ├── Constants.kt                    # 스토리지 상수
│   ├── TableFileManager.kt             # 파일 I/O (atomic write)
│   ├── RowEncoder.kt                   # 행 바이너리 인코딩/디코딩
│   ├── FieldEncoder.kt                 # 필드 인코더 인터페이스
│   ├── IntFieldEncoder.kt              # INT 인코더 (4바이트 Big Endian)
│   ├── VarcharFieldEncoder.kt          # VARCHAR 인코더 (2바이트 길이 + UTF-8)
│   ├── BooleanFieldEncoder.kt          # BOOLEAN 인코더 (1바이트)
│   ├── TimestampFieldEncoder.kt        # TIMESTAMP 인코더 (8바이트)
│   ├── BufferPool.kt                   # LRU 기반 16KB 페이지 버퍼 풀
│   ├── Page.kt                         # 페이지 모델
│   └── PageManager.kt                  # 페이지 단위 디스크 I/O
├── vacuum/
│   ├── VacuumService.kt                # Vacuum(삭제된 행 정리) 실행
│   ├── VacuumScheduler.kt              # 주기적 Vacuum 스케줄링
│   ├── VacuumConfig.kt                 # Vacuum 설정
│   ├── VacuumConfiguration.kt          # Spring 설정
│   └── VacuumLockManager.kt            # Vacuum 락 관리
├── elasticsearch/
│   ├── config/ElasticsearchConfig.kt   # ES 설정
│   ├── service/                        # EXPLAIN, 메타데이터, 통계 서비스
│   ├── repository/                     # ES 레포지토리
│   ├── document/                       # ES 문서 모델
│   └── example/                        # EXPLAIN 예제
├── exception/                          # 커스텀 예외 (TypeMismatch, ColumnNotFound 등)
└── validation/
    └── TypeValidator.kt                # 타입 검증

test/kotlin/study/db/server/            # 유닛 테스트 및 통합 테스트
```

## 핵심 컴포넌트

- **TableService**: ConcurrentHashMap 기반 스레드 안전 테이블 관리, 디스크 영속성
  - `select()` 파이프라인: WHERE 필터링 → ORDER BY 정렬 → LIMIT/OFFSET → 컬럼 프로젝션
- **Storage Layer**: 커스텀 바이너리 포맷, 페이지 기반 버퍼 풀 (16KB, LRU)
- **Vacuum**: 삭제 마킹된 행을 물리적으로 정리 (MySQL Purge 방식)
- **SQL Parser**: CREATE, INSERT, SELECT(WHERE/ORDER BY/LIMIT/OFFSET/컬럼 선택), DELETE, UPDATE, EXPLAIN 파싱
- **Resolver**: INSERT/UPDATE 타입 검증, SELECT/ORDER BY 컬럼 존재 검증
- **Elasticsearch**: EXPLAIN 쿼리 실행 계획 분석

## DML 구현 현황

| 명령어 | 파싱/라우팅 | 실행 로직 |
|--------|------------|-----------|
| CREATE TABLE | 완료 | 완료 |
| INSERT | 완료 | 완료 |
| SELECT | 완료 | 완료 |
| DELETE | 완료 | 완료 |
| UPDATE | 완료 (Phase 2 Step 1) | TODO (Step 3, 4) — 검증 완료 (Step 2) |

### UPDATE 파싱 규칙 (ConnectionHandler.kt)
- 정규식: `UPDATE\s+(\w+)\s+SET\s+(.+?)(?:\s+WHERE\s+(.+))?$`
- SET 값: `"val"`, `'val'`, 따옴표 없는 `val` 모두 지원
- WHERE 절 없으면 전체 행 업데이트 (whereString = null)
- TableService.update() stub 호출 → 실제 구현은 Step 3, 4에서 완료 예정
