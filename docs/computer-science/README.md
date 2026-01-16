# 컴퓨터 과학 개념 정리

이 디렉터리는 In-Memory Database 프로젝트에서 사용된 컴퓨터 과학 개념들을 파트별로 정리한 문서입니다. 각 문서는 이론적 배경, 실제 구현 코드, 그리고 실무 활용 사례를 포함합니다.

---

## 문서 구성

### [01. 네트워크 프로그래밍 & 분산 시스템](./01-network-programming.md)

데이터베이스의 서버-클라이언트 아키텍처, TCP/IP 통신, 연결 관리에 대해 다룹니다.

**주요 내용**:
- TCP/IP 서버 구현 (`DbTcpServer`)
- 연결 관리 시스템 (`ConnectionManager`)
- 인증 프로토콜 (Handshake, Authentication)
- 커스텀 프로토콜 설계 (`DbRequest`, `DbResponse`)
- 스레드 풀링과 리소스 관리
  docs에서 전반적으로 코드의 양은 줄이고, 반복되는 코드는 ...(중략 표시)를 통해서 줄이고, 어떤 개념이 소개되면 그 개념에 대해서 설명을 해줘. 그 것이 MySQL에서 어떻게 활용 되었는지 까지 설명해주면 좋을 것같아.
**핵심 개념**:
- 소켓 프로그래밍
- 프로토콜 설계
- 연결 풀링
- 상태 머신

**실무 연관성**:
- MySQL의 Connection Layer
- PostgreSQL의 Wire Protocol
- 분산 시스템의 통신 패턴

---

### [02. 동시성 제어 (Concurrency Control)](./02-concurrency-control.md)

여러 클라이언트가 동시에 데이터에 접근할 때 일관성을 보장하는 방법을 설명합니다.

**주요 내용**:
- Race Condition과 Lost Update 문제
- `ConcurrentHashMap`을 사용한 스레드 안전성
- `AtomicLong`을 사용한 Lock-free 연산
- `compute()`, `putIfAbsent()` 등 원자적 연산
- 스레드 풀 (Thread Pool)

**핵심 개념**:
- CAS (Compare-And-Swap)
- 낙관적/비관적 락킹
- Lock-free 알고리즘
- 스레드 안전성

**실무 연관성**:
- InnoDB의 Row-level Locking
- PostgreSQL의 MVCC
- 동시성 버그 디버깅

---

### [03. 데이터베이스 아키텍처 & 물리 구조](./03-database-architecture-and-storage.md)

데이터를 디스크에 저장하고 효율적으로 읽어오는 저장소 엔진을 다룹니다.

**주요 내용**:
- 페이지 기반 저장소 (`Page`, `PageManager`)
- 디스크 I/O 최적화 (Sequential vs Random I/O)
- 데이터 직렬화 (`ByteBuffer`, Big Endian)
- 행 인코더 (`RowEncoder`, `IntFieldEncoder`)
- `RandomAccessFile`을 사용한 파일 I/O

**핵심 개념**:
- 페이지 (Page)
- 버퍼 풀 (Buffer Pool)
- 순차 I/O의 중요성
- 직렬화/역직렬화

**실무 연관성**:
- InnoDB의 16KB 페이지
- PostgreSQL의 힙 파일
- Write-Ahead Logging (WAL)

---

### [04. 쿼리 처리 파이프라인](./04-query-processing-pipeline.md)

SQL 문자열이 실행 결과로 변환되는 전체 과정을 컴파일러와 비교하여 설명합니다.

**주요 내용**:
- 파싱 (Parsing) - SQL → AST
- 의미 분석 (Semantic Analysis)
- 쿼리 표현 (`ParsedQuery`, `WhereCondition`)
- 쿼리 실행 (Volcano Iterator Model)

**핵심 개념**:
- 추상 구문 트리 (AST)
- Lexer와 Parser
- 의미 검증
- 실행 모델

**실무 연관성**:
- MySQL의 Query Execution 과정
- PostgreSQL의 Planner
- 컴파일러 이론과의 유사성

---

### [05. 쿼리 최적화 (Query Optimization)](./05-query-optimization.md)

같은 결과를 반환하는 여러 실행 방법 중 가장 효율적인 방법을 선택하는 과정을 다룹니다.

**주요 내용**:
- `EXPLAIN` 기능 구현 (`ExplainService`)
- 비용 기반 최적화 (Cost-Based Optimization)
- 선택도 (Selectivity) 계산
- 인덱스 선택 전략
- 커버드 쿼리 (Covered Query)

**핵심 개념**:
- 비용 모델 (I/O + CPU + 메모리)
- Cardinality 추정
- 실행 계획 (Execution Plan)
- 인덱스 최적화

**실무 연관성**:
- MySQL의 EXPLAIN 출력
- PostgreSQL의 EXPLAIN ANALYZE
- 쿼리 튜닝 기법

---

### [06. 통계 & 메타데이터 관리](./06-statistics-and-metadata.md)

데이터베이스의 구조 정보(메타데이터)와 데이터 분포(통계)를 관리하는 방법을 설명합니다.

**주요 내용**:
- 테이블 메타데이터 (`TableMetadata`)
- 인덱스 메타데이터 (`IndexMetadata`)
- 테이블 통계 (`TableStatistics`, `ColumnStatistics`)
- 히스토그램 (Histogram)
- 통계 갱신 전략

**핵심 개념**:
- 시스템 카탈로그
- Distinct Count (Cardinality)
- 선택도 (Selectivity)
- 등폭/등깊이 히스토그램

**실무 연관성**:
- MySQL의 `information_schema`
- PostgreSQL의 `pg_stats`
- `ANALYZE TABLE` 명령

---

### [07. 로깅 & 모니터링](./07-logging-and-monitoring.md)

운영 환경에서 데이터베이스의 동작을 추적하고 성능을 분석하는 방법을 다룹니다.

**주요 내용**:
- 쿼리 로그 (`QueryLog`)
- Slow Query Log
- Elasticsearch 통합
- Kibana 대시보드
- 실시간 메트릭 수집 (Prometheus + Grafana)

**핵심 개념**:
- 구조화된 로깅
- 로그 집계 및 검색
- 성능 메트릭
- 인덱스 라이프사이클 관리 (ILM)

**실무 연관성**:
- MySQL의 General/Slow Query Log
- PostgreSQL의 Logging 설정
- ELK Stack (Elasticsearch, Logstash, Kibana)

---

## 학습 순서 추천

### 초보자
1. **네트워크 프로그래밍** → 서버-클라이언트 기초 이해
2. **데이터베이스 아키텍처** → 데이터 저장 방식 이해
3. **쿼리 처리 파이프라인** → SQL이 실행되는 과정 파악

### 중급자
4. **동시성 제어** → 멀티스레드 환경의 문제 해결
5. **통계 & 메타데이터** → 옵티마이저가 사용하는 정보
6. **쿼리 최적화** → 성능 튜닝의 핵심

### 고급자
7. **로깅 & 모니터링** → 운영 환경의 실전 기술

---

## 프로젝트에서 사용된 주요 기술

### 언어 & 프레임워크
- **Kotlin** 1.9.25
- **Spring Boot** 3.5.3
- Java 17

### 데이터 저장 & 검색
- **Elasticsearch** 8.11.1 (메타데이터, 로그 저장)
- **Kibana** 8.11.1 (시각화)
- In-Memory Storage (ConcurrentHashMap)

### 동시성 & 네트워킹
- `ConcurrentHashMap`, `AtomicLong`
- `ServerSocket`, `RandomAccessFile`
- Thread Pool (`Executors`)

### SQL 파싱 & 실행
- **JSqlParser** (SQL 파싱)
- 커스텀 쿼리 실행 엔진

---

## 실무 적용 포인트

### 1. 네트워크 프로그래밍
- 마이크로서비스 간 통신 설계
- API 서버 구현
- 프로토콜 설계 능력

### 2. 동시성 제어
- 멀티스레드 환경의 버그 해결
- 락 최소화 전략
- 성능과 안전성의 균형

### 3. 저장소 최적화
- 디스크 I/O 최소화
- 캐싱 전략
- 데이터 압축 및 인코딩

### 4. 쿼리 튜닝
- `EXPLAIN` 분석 능력
- 인덱스 설계
- 슬로우 쿼리 해결

### 5. 운영 & 모니터링
- 로그 기반 문제 진단
- 성능 메트릭 해석
- 알림 시스템 구축

---

## 참고 자료

### 책
- **Database Internals** - Alex Petrov
- **Designing Data-Intensive Applications** - Martin Kleppmann
- **Database System Concepts** - Silberschatz, Korth, Sudarshan
- **Java Concurrency in Practice** - Brian Goetz

### 공식 문서
- [MySQL Documentation](https://dev.mysql.com/doc/)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Elasticsearch Guide](https://www.elastic.co/guide/)
- [Kotlin Coroutines Guide](https://kotlinlang.org/docs/coroutines-guide.html)

### 온라인 리소스
- [Use The Index, Luke!](https://use-the-index-luke.com/)
- [Database Internals Blog](https://www.databass.dev/)
- [High Scalability](http://highscalability.com/)

---

## 기여 및 피드백

이 문서는 학습 목적으로 작성되었으며, 계속해서 업데이트될 예정입니다.

**개선 제안**이나 **추가 학습 제안**이 있다면 이슈를 생성해주세요.

---

## 라이센스

이 문서는 프로젝트와 동일한 라이센스를 따릅니다.
