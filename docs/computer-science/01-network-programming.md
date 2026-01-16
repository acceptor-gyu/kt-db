# 네트워크 프로그래밍 & 분산 시스템

## 개요

데이터베이스는 네트워크를 통해 여러 클라이언트가 동시에 접속하여 쿼리를 실행할 수 있어야 합니다. 이 장에서는 TCP/IP 기반의 서버-클라이언트 아키텍처 구현 방법과 실제 MySQL에서 어떻게 활용되는지 알아봅니다.

---

## 1. TCP/IP 서버 구현

### 핵심 개념: TCP 프로토콜

**TCP(Transmission Control Protocol)**는 신뢰성 있는 연결 지향 프로토콜입니다.

**왜 TCP를 사용하는가?**
- **신뢰성**: 패킷 손실 시 자동 재전송
- **순서 보장**: 데이터가 전송한 순서대로 도착
- **흐름 제어**: 수신자의 처리 속도에 맞춰 전송 속도 조절
- **혼잡 제어**: 네트워크 상황에 따라 전송량 조절

**UDP vs TCP 비교**:
| 특성 | UDP | TCP |
|-----|-----|-----|
| 연결 | 비연결형 | 연결형 (3-way handshake) |
| 신뢰성 | 보장 안 함 | 보장 |
| 순서 | 보장 안 함 | 보장 |
| 속도 | 빠름 | 상대적으로 느림 |
| 용도 | DNS, 스트리밍 | HTTP, 데이터베이스, 파일 전송 |

### MySQL의 TCP 사용

**MySQL은 TCP 포트 3306을 사용합니다**:
```bash
# MySQL 서버 시작
mysqld --port=3306

# 클라이언트 연결
mysql -h localhost -P 3306 -u root -p
```

**연결 과정**:
1. 클라이언트가 TCP 3306 포트로 연결 요청
2. 3-way handshake로 TCP 연결 수립
3. MySQL 프로토콜 핸드셰이크 시작
4. 인증 후 쿼리 전송/수신

### 구현 코드 (핵심만)

**파일**: `DbTcpServer.kt:37-65`

```kotlin
class DbTcpServer(private val port: Int, private val maxConnections: Int = 10) {
    private val executor = Executors.newFixedThreadPool(maxConnections)
    private val connectionManager = ConnectionManager()

    fun start() {
        val serverSocket = ServerSocket(port)  // 포트 바인딩

        while (running) {
            val clientSocket = serverSocket.accept()  // ← 블로킹, 연결 대기

            // 연결 제한 확인
            if (connectionManager.getActiveCount() >= maxConnections) {
                clientSocket.close()
                continue
            }

            // 연결 처리
            val connectionId = connectionManager.generateConnectionId()
            val handler = ConnectionHandler(connectionId, clientSocket, ...)

            executor.submit(handler)  // 스레드 풀에서 실행
            connectionManager.register(handler)
        }
    }

    // ...(graceful shutdown 로직)
}
```

### 주요 개념 설명

#### 1) ServerSocket.accept() - 블로킹 I/O

```kotlin
val clientSocket = serverSocket.accept()  // 여기서 대기
```

**블로킹(Blocking)**이란?
- 클라이언트가 연결할 때까지 이 줄에서 멈춤
- 연결이 오면 다음 줄로 진행
- 메인 스레드가 멈추므로 다른 작업 불가

**MySQL의 처리**:
- 메인 스레드에서 accept() 호출
- 연결이 오면 워커 스레드에 전달하여 처리

#### 2) 스레드 풀(Thread Pool)

```kotlin
val executor = Executors.newFixedThreadPool(10)  // 최대 10개 스레드
executor.submit(handler)  // 작업 제출
```

**스레드 풀이란?**
- 미리 생성한 스레드를 재사용하는 패턴
- **장점**: 스레드 생성/소멸 비용 절감, 리소스 제어
- **단점**: 스레드 수 이상의 동시 요청 처리 불가

**MySQL의 스레드 관리**:
```sql
-- MySQL 5.6 이전: Thread-per-connection (연결당 1개 스레드)
SHOW VARIABLES LIKE 'max_connections';  -- 기본값: 151

-- MySQL 5.7+: Thread Pool 플러그인 지원
SHOW VARIABLES LIKE 'thread_pool_size';  -- CPU 코어 수
```

#### 3) Graceful Shutdown

**Graceful Shutdown이란?**
- 처리 중인 작업을 안전하게 완료한 후 종료
- 데이터 손실 방지 및 클라이언트에 연결 종료 알림

**MySQL의 Shutdown 과정**:
```bash
mysqladmin shutdown

# 내부 동작:
# 1. 새 연결 차단 (ServerSocket.close())
# 2. 기존 연결의 쿼리 완료 대기
# 3. 버퍼 플러시 (메모리 → 디스크)
# 4. 프로세스 종료
```

---

## 2. 연결 관리 시스템

### 핵심 개념: Connection Pooling

**Connection Pooling**은 제한된 리소스(연결, 스레드)를 효율적으로 관리하는 패턴입니다.

**왜 필요한가?**
- 무제한 연결 허용 시 메모리 부족 (OOM)
- 스레드 수 초과 시 컨텍스트 스위칭 오버헤드 증가
- DoS 공격 방지

### 구현 코드 (핵심만)

**파일**: `ConnectionManager.kt:34-84`

```kotlin
class ConnectionManager {
    private val connections = ConcurrentHashMap<Long, ConnectionHandler>()
    private val connectionIdGenerator = AtomicLong(0)  // Thread-safe ID 생성

    fun generateConnectionId(): Long {
        return connectionIdGenerator.incrementAndGet()  // 원자적 증가
    }

    fun register(handler: ConnectionHandler) {
        connections[handler.connectionId] = handler
    }

    fun getActiveCount(): Int {
        return connections.size
    }

    // ...(unregister, closeAll 등)
}
```

### 주요 개념 설명

#### 1) AtomicLong - Lock-free 연산

```kotlin
private val connectionIdGenerator = AtomicLong(0)
val id = connectionIdGenerator.incrementAndGet()  // 원자적 증가
```

**AtomicLong이란?**
- `long` 타입의 원자적 연산 제공
- **CAS(Compare-And-Swap)** 알고리즘 사용
- Lock 없이도 Thread-safe 보장

**왜 사용하는가?**
```kotlin
// ❌ 잘못된 방법 (Race Condition 발생)
var counter = 0L
fun generateId(): Long {
    counter++  // 읽기 → 증가 → 쓰기 (3단계가 원자적이지 않음)
    return counter
}
// Thread 1: 5 읽음 → 6으로 증가 → 쓰기 전 대기
// Thread 2: 5 읽음 → 6으로 증가 → 쓰기
// 결과: 중복 ID 생성! ❌

// ✅ 올바른 방법 (AtomicLong 사용)
val counter = AtomicLong(0)
fun generateId(): Long {
    return counter.incrementAndGet()  // 원자적 연산
}
```

**MySQL의 연결 ID 관리**:
```sql
SELECT CONNECTION_ID();  -- 현재 연결의 고유 ID
-- MySQL도 내부적으로 원자적 증가 카운터 사용
```

#### 2) 연결 풀 제한

```kotlin
if (connectionManager.getActiveCount() >= maxConnections) {
    clientSocket.close()  // 연결 거부
}
```

**MySQL의 연결 제한**:
```sql
-- 최대 연결 수 설정
SET GLOBAL max_connections = 200;

-- 현재 연결 수 확인
SHOW STATUS LIKE 'Threads_connected';

-- 연결 한계 도달 시 에러
-- ERROR 1040 (HY000): Too many connections
```

---

## 3. 인증 프로토콜

### 핵심 개념: Handshake & Authentication

**Handshake**는 클라이언트와 서버가 통신을 시작하기 전 초기화 과정입니다.

**왜 필요한가?**
- 서버 버전 및 기능 협상
- 암호화 방식 결정
- 인증 준비 (Salt 전송 등)

### 프로토콜 흐름

```
클라이언트                              서버
    |                                 |
    |-- TCP 연결 요청 ----------------->|
    |                                 |
    |<-- "HANDSHAKE|v1.0" ------------|  서버 정보 전송
    |                                 |
    |-- "AUTH|user|password" -------->|  자격증명 전송
    |                                 |
    |<-- "Authenticated" -------------|  인증 성공
    |                                 |
    |-- DbRequest (직렬화) ------------>|  쿼리 실행
    |                                 |
    |<-- DbResponse (직렬화) ----------|  결과 반환
```

### 구현 코드 (핵심만)

**파일**: `ConnectionHandler.kt:120-144`

```kotlin
class ConnectionHandler(val connectionId: Long, private val socket: Socket, ...) : Runnable {
    private var state = ConnectionState.CONNECTED

    override fun run() {
        try {
            sendHandshake()  // 1. 핸드셰이크 전송
            state = ConnectionState.HANDSHAKE_SENT

            while (!socket.isClosed) {
                val message = input.readUTF()
                when (state) {
                    HANDSHAKE_SENT -> handleAuth(message)  // 2. 인증 처리
                    AUTHENTICATED -> handleCommand(message)  // 3. 명령 실행
                    // ...
                }
            }
        } finally {
            close()  // 리소스 정리
        }
    }

    private fun handleAuth(message: String) {
        // "AUTH|username|password" 파싱 및 검증
        if (user == authenticatedUser && password == authenticatedPassword) {
            state = ConnectionState.AUTHENTICATED
            output.writeUTF("Authenticated")
        } else {
            output.writeUTF("ER_ACCESS_DENIED")
            socket.close()
        }
    }
}
```

### MySQL의 인증 프로토콜

**MySQL Handshake 과정**:
```
1. Initial Handshake Packet (서버 → 클라이언트)
   - 서버 버전: "8.0.32"
   - 인증 플러그인: "caching_sha2_password"
   - Salt (난수): 20 바이트

2. Handshake Response (클라이언트 → 서버)
   - 사용자명: "root"
   - 암호화된 비밀번호: SHA256(password + salt)
   - 클라이언트 플래그: LONG_PASSWORD, PROTOCOL_41, ...

3. OK Packet (서버 → 클라이언트)
   - 인증 성공
```

**MySQL의 인증 플러그인**:
```sql
-- 사용 가능한 인증 플러그인 확인
SHOW PLUGINS WHERE Type = 'AUTHENTICATION';

-- 사용자별 인증 방식
SELECT user, host, plugin FROM mysql.user;
```

---

## 4. 커스텀 프로토콜 설계

### 핵심 개념: 직렬화 (Serialization)

**직렬화**는 메모리의 객체를 바이트 스트림으로 변환하여 전송/저장하는 과정입니다.

**왜 필요한가?**
- 네트워크는 바이트만 전송 가능
- 객체를 파일이나 네트워크로 전달하려면 직렬화 필요

### 구현 코드 (핵심만)

**파일**: `DbRequest.kt`, `ProtocolCodec.kt`

```kotlin
// 요청/응답 객체 정의
@Serializable
data class DbRequest(
    val command: DbCommand,  // CREATE_TABLE, INSERT, SELECT, ...
    val tableName: String?,
    val sql: String?,
    // ...
)

@Serializable
data class DbResponse(
    val success: Boolean,
    val data: String?,
    val errorMessage: String?,
    // ...
)

// 직렬화/역직렬화
object ProtocolCodec {
    private val json = Json { /* 설정 */ }

    fun encodeRequest(request: DbRequest): ByteArray {
        return json.encodeToString(request).toByteArray()
    }

    fun decodeRequest(bytes: ByteArray): DbRequest {
        return json.decodeFromString(String(bytes))
    }
}
```

### 직렬화 방식 비교

| 형식 | 크기 | 속도 | 가독성 | 용도 |
|-----|------|------|--------|------|
| **JSON** | 큼 | 보통 | 높음 | REST API, 설정 파일 |
| **Protobuf** | 작음 | 빠름 | 낮음 | gRPC, 내부 통신 |
| **MessagePack** | 작음 | 빠름 | 낮음 | 바이너리 프로토콜 |
| **Java Serialization** | 큼 | 느림 | 낮음 | Java 객체 전송 |

**MySQL의 프로토콜**:
- **MySQL Protocol**: 바이너리 형식
- 필드마다 타입과 길이 정보 포함
- 예: `[길이: 3바이트][타입: 1바이트][데이터]`

---

## 실무 활용 정리

### MySQL과의 비교

| 기능 | 이 프로젝트 | MySQL |
|-----|-----------|-------|
| **프로토콜** | TCP + 커스텀 프로토콜 | TCP + MySQL Protocol |
| **포트** | 설정 가능 | 3306 (기본값) |
| **스레드 모델** | Thread Pool (고정) | Thread-per-connection 또는 Thread Pool |
| **연결 제한** | maxConnections | max_connections (151) |
| **인증** | 간단한 문자열 비교 | 플러그인 기반 (SHA256, LDAP, ...) |
| **직렬화** | JSON | 바이너리 프로토콜 |

### 성능 최적화 팁

**1. 스레드 풀 크기 결정**:
```kotlin
// CPU 집약적 작업: CPU 코어 수 + 1
val poolSize = Runtime.getRuntime().availableProcessors() + 1

// I/O 집약적 작업 (DB): 더 큰 풀
val poolSize = Runtime.getRuntime().availableProcessors() * 2
```

**2. 소켓 옵션 설정**:
```kotlin
socket.soTimeout = 30_000  // 30초 타임아웃
socket.keepAlive = true    // TCP Keep-Alive
socket.tcpNoDelay = true   // Nagle 알고리즘 비활성화 (지연 감소)
```

**3. MySQL 연결 풀 사용**:
```java
// HikariCP (가장 빠른 JDBC Connection Pool)
HikariConfig config = new HikariConfig();
config.setMaximumPoolSize(20);  // 최대 연결 수
config.setMinimumIdle(5);       // 최소 유휴 연결
config.setConnectionTimeout(30000);  // 연결 대기 시간
```

---

## 참고 자료

- [MySQL Client/Server Protocol](https://dev.mysql.com/doc/internals/en/client-server-protocol.html)
- [PostgreSQL Wire Protocol](https://www.postgresql.org/docs/current/protocol.html)
- [Java NIO Tutorial](https://docs.oracle.com/javase/tutorial/essential/io/index.html)
- [HikariCP Documentation](https://github.com/brettwooldridge/HikariCP)
