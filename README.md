# kt-db

Kotlin으로 구현하는 In-Memory Database

데이터베이스를 처음부터 구현하면서 Kotlin과 네트워크 프로그래밍, 동시성 제어를 학습하는 프로젝트입니다.

## 프로젝트 구조

```
┌─────────────────────────────────────────────────────────────┐
│                        Client Apps                           │
│                    (Multiple Connections)                    │
└────────────────────┬────────────────────────────────────────┘
                     │ TCP/IP
                     ▼
┌─────────────────────────────────────────────────────────────┐
│                       DbTcpServer                            │
│  - 클라이언트 연결 수락                                        │
│  - ConnectionManager, TableService 싱글톤 관리                │
└───────────┬─────────────────────────────────────────────────┘
            │
            ├──────────────────┬──────────────────┐
            ▼                  ▼                  ▼
    ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
    │ Connection #1│   │ Connection #2│   │ Connection #N│
    │  Handler     │   │  Handler     │   │  Handler     │
    │ (Thread A)   │   │ (Thread B)   │   │ (Thread N)   │
    └──────┬───────┘   └──────┬───────┘   └──────┬───────┘
           │                  │                  │
           └──────────────────┼──────────────────┘
                              │ 공유 접근 (Thread-safe)
                              ▼
           ┌──────────────────────────────────────┐
           │       ConnectionManager              │
           │  - Connection ID 생성 (AtomicLong)   │
           │  - 활성 연결 추적 (ConcurrentHashMap)│
           │  - register() / unregister()         │
           └──────────────────────────────────────┘
                              │
                              ▼
           ┌──────────────────────────────────────┐
           │         TableService                 │
           │  - 테이블 생성/조회/삭제/수정          │
           │  - ConcurrentHashMap 기반            │
           │  - Atomic Operations (compute 등)   │
           │  - 모든 연결이 공유하는 단일 인스턴스   │
           └──────────────────────────────────────┘
```

### 주요 컴포넌트

#### 1. DbTcpServer
- **역할**: TCP 연결 수락 및 ConnectionHandler 생성
- **인스턴스**: 1개 (서버 시작 시)
- **책임**:
  - ServerSocket으로 클라이언트 연결 수락
  - ConnectionManager와 TableService 싱글톤 관리
  - 각 연결마다 새 ConnectionHandler 생성 및 스레드 풀에 제출

#### 2. ConnectionManager
- **역할**: 연결 생명주기 관리
- **인스턴스**: 1개 (DbTcpServer가 소유)
- **책임**:
  - Connection ID 생성 (AtomicLong 기반)
  - 활성 연결 추적 (ConcurrentHashMap)
  - 연결 등록/해제
  - SHOW PROCESSLIST, KILL 명령 지원 (향후)

#### 3. ConnectionHandler
- **역할**: 개별 클라이언트 연결 처리
- **인스턴스**: 클라이언트 연결당 1개
- **책임**:
  - 핸드셰이크 및 인증
  - 클라이언트 명령 파싱 및 처리
  - TableService 호출
  - 연결 종료 시 리소스 정리 및 ConnectionManager에서 unregister

#### 4. TableService
- **역할**: In-memory 데이터 저장소
- **인스턴스**: 1개 (모든 연결이 공유)
- **Thread-Safety**:
  - `ConcurrentHashMap` 사용
  - `compute()`, `putIfAbsent()` 등 atomic operation
  - 여러 ConnectionHandler의 동시 접근 안전

## 요청 처리 흐름

```
┌─────────┐                                              ┌──────────────┐
│ Client  │                                              │ DbTcpServer  │
└────┬────┘                                              └──────┬───────┘
     │                                                           │
     │ 1. TCP Connection Request                                │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                    2. Generate Connection ID
     │                                       (ConnectionManager.generateConnectionId())
     │                                                           │
     │                                    3. Create ConnectionHandler
     │                                       - connectionId: 1   │
     │                                       - socket            │
     │                                       - tableService      │
     │                                       - connectionManager │
     │                                                           │
     │                                    4. Register Connection
     │                                       (ConnectionManager.register())
     │                                                           │
     │                                    5. Submit to Thread Pool
     │                                       (executor.submit())
     │                                                           │
     │                                                      ┌────▼────────┐
     │                                                      │Connection   │
     │                                                      │Handler #1   │
     │                                                      │(Thread A)   │
     │                                                      └────┬────────┘
     │ 6. Send Handshake (HANDSHAKE|v1.0)                       │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 7. Auth Request (AUTH|user|password)                     │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      8. Verify Credentials
     │                                                           │
     │ 9. Auth Response (Authenticated)                         │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 10. Command Request                                      │
     │     (CREATE TABLE users ...)                             │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      11. Parse Command   │
     │                                                           │
     │                                      12. Call TableService
     │                                          (thread-safe)   │
     │                                          ┌────▼────────┐ │
     │                                          │TableService │ │
     │                                          │(Shared)     │ │
     │                                          │ConcurrentMap│ │
     │                                          └────┬────────┘ │
     │                                               │          │
     │                                      13. Execute Operation
     │                                          (CREATE/INSERT/SELECT)
     │                                               │          │
     │                                          ┌────▼────────┐ │
     │                                          │  Result     │ │
     │                                          └────┬────────┘ │
     │                                               │          │
     │ 14. Response                              ◀──┘          │
     │◀─────────────────────────────────────────────────────────┤
     │                                                           │
     │ 15. Close Connection                                     │
     │──────────────────────────────────────────────────────────▶│
     │                                                           │
     │                                      16. Close Resources │
     │                                          - input.close() │
     │                                          - output.close()│
     │                                          - socket.close()│
     │                                                           │
     │                                      17. Unregister      │
     │                                          (ConnectionManager.unregister())
     │                                                           │
     └───────────────────────────────────────────────────────────┘
```

## Thread-Safety 보장

### ConnectionManager
- `AtomicLong`을 사용한 Connection ID 생성 (동시 호출 시 고유 ID 보장)
- `ConcurrentHashMap`으로 활성 연결 추적 (동시 읽기/쓰기 안전)

### TableService
- `ConcurrentHashMap` 사용
- **Atomic Operations**:
  - `createTable()`: `putIfAbsent()` - 중복 생성 방지
  - `insert()`: `compute()` - read-modify-write를 atomic하게 처리
  - `dropTable()`: `remove()` - atomic 삭제

#### insert() Race Condition 방지 예시

```kotlin
// ❌ Thread-unsafe (Race Condition 발생!)
val table = tables[tableName]           // Thread A, B 동시 읽기
val updated = table.copy(value = ...)   // Thread A, B 각자 수정
tables[tableName] = updated             // 하나의 수정이 손실됨!

// ✅ Thread-safe (Atomic Operation)
tables.compute(tableName) { _, existingTable ->
    existingTable?.copy(value = existingTable.value + values)
}
// compute() 내부는 하나의 atomic operation
// Thread B는 Thread A가 끝날 때까지 대기
```

## Connection ID 관리

```kotlin
// 초기 상태
connectionIdGenerator = AtomicLong(0)

// 연결 1~10 생성
generateConnectionId() → 1, 2, 3, ..., 10

// 연결 3, 7 종료
unregister(3)  // ID generator는 변하지 않음 (여전히 10)
unregister(7)  // connections Map에서만 제거

// 새 연결 5개 생성
generateConnectionId() → 11, 12, 13, 14, 15  // 3과 7을 재사용하지 않음!

// 활성 연결: {1, 2, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 15}
// ID는 절대 재사용되지 않음 (로그 추적에 유리)
```

## 개발 환경

- **언어**: Kotlin 1.9.25
- **프레임워크**: Spring Boot 3.5.3
- **JDK**: Java 17
- **빌드 도구**: Gradle

## 빌드 및 실행

```bash
# 빌드
./gradlew build

# 서버 실행
./gradlew bootRun

# 테스트
./gradlew test
```
