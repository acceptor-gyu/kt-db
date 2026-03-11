# 네트워크에서의 Exponential Backoff 알고리즘

vacuum에서 Exponential Backoff를 보고 예전에 네트워크에 대해 학습할 때 비슷한 내용이 생각난 김에 네트워크에서 Exponential Backoff도 알아봤습니다.

## 목차
1. [Exponential Backoff란?](#1-exponential-backoff란)
2. [Ethernet CSMA/CD - Binary Exponential Backoff](#2-ethernet-csmacd---binary-exponential-backoff)
3. [TCP - Retransmission Timeout (RTO)](#3-tcp---retransmission-timeout-rto)
4. [Wi-Fi (802.11) - Contention Window](#4-wi-fi-80211---contention-window)
5. [HTTP API - Exponential Backoff + Jitter](#5-http-api---exponential-backoff--jitter)
6. [DNS - Query Retry](#6-dns---query-retry)
7. [알고리즘 비교 및 선택 가이드](#7-알고리즘-비교-및-선택-가이드)

---

## 1. Exponential Backoff란?

### 정의

**실패 또는 충돌 발생 시 재시도 간격을 지수적으로 증가시키는 알고리즘**

```
시도 횟수  | 대기 시간
----------|------------
1회 실패  | 1초
2회 실패  | 2초
3회 실패  | 4초
4회 실패  | 8초
5회 실패  | 16초
```

### 왜 필요한가?

#### 문제: 고정 재시도 간격

```java
// 나쁜 예: 고정 간격
public boolean sendPacketBad() throws InterruptedException {
    for (int attempt = 0; attempt < 10; attempt++) {
        if (send()) {
            return true;
        }
        Thread.sleep(1000);  // 항상 1초 대기
    }
    return false;
}
```

**문제점:**
```
컴퓨터 A: 충돌 → 1초 대기 → 재전송 → 충돌
컴퓨터 B: 충돌 → 1초 대기 → 재전송 → 충돌
컴퓨터 C: 충돌 → 1초 대기 → 재전송 → 충돌
→ 계속 충돌 반복 (Synchronized Collision)
```

#### 해결: Exponential Backoff

```java
// 좋은 예: 지수 백오프
public boolean sendPacketGood() throws InterruptedException {
    long delay = 1000;  // 1초 (밀리초)

    for (int attempt = 0; attempt < 10; attempt++) {
        if (send()) {
            return true;
        }
        Thread.sleep(delay);
        delay = Math.min(delay * 2, 60000);  // 지수 증가, 최대 60초
    }
    return false;
}
```

**효과:**
```
컴퓨터 A: 충돌 → 1초 대기 → 재전송 → 성공!
컴퓨터 B: 충돌 → 2초 대기 → 재전송 → 성공!
컴퓨터 C: 충돌 → 4초 대기 → 재전송 → 성공!
→ 시간차 발생으로 충돌 회피
```

---

## 2. Ethernet CSMA/CD - Binary Exponential Backoff

### 개요

**Ethernet에서 충돌 발생 시 재전송 대기 시간을 결정하는 알고리즘**

- **프로토콜:** IEEE 802.3 Ethernet
- **계층:** Data Link Layer (Layer 2)
- **목적:** 공유 매체에서 다수의 장치가 충돌 없이 통신

### CSMA/CD 동작 과정

```
1. Carrier Sense (CS): 매체가 사용 중인지 감지
   └─ Busy → 대기
   └─ Idle → 전송 시작

2. Multiple Access (MA): 여러 장치가 동시 접근 가능
   └─ 모든 장치가 같은 매체 공유

3. Collision Detection (CD): 충돌 감지
   └─ 전송 중 신호 충돌 감지
   └─ Jam Signal 전송 (충돌 알림)
   └─ Binary Exponential Backoff 실행
```

### Binary Exponential Backoff 알고리즘

#### 핵심 공식

```
충돌 횟수 = n
대기 슬롯 범위 = 0 ~ (2^k - 1), 단 k = min(n, 10)
실제 대기 시간 = 랜덤 슬롯 × 51.2μs
```

#### 상세 표

```
충돌 횟수  | k  | 슬롯 범위    | 최대 대기 시간
----------|----|--------------|-----------------
1회       | 1  | 0 ~ 1        | 51.2μs
2회       | 2  | 0 ~ 3        | 153.6μs
3회       | 3  | 0 ~ 7        | 358.4μs
4회       | 4  | 0 ~ 15       | 768μs
5회       | 5  | 0 ~ 31       | 1.59ms
6회       | 6  | 0 ~ 63       | 3.23ms
7회       | 7  | 0 ~ 127      | 6.50ms
8회       | 8  | 0 ~ 255      | 13.1ms
9회       | 9  | 0 ~ 511      | 26.2ms
10회      | 10 | 0 ~ 1023     | 52.4ms
11회      | 10 | 0 ~ 1023     | 52.4ms (고정)
...
16회      | -  | 포기         | Frame Drop
```

### 구현 예시 (Java)

```java
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class EthernetTransmitter {
    // Ethernet 상수
    private static final double SLOT_TIME = 0.0000512;  // 51.2μs (10Mbps Ethernet)
    private static final int MAX_COLLISIONS = 16;
    private final Random random = new Random();

    /**
     * Ethernet 프레임 전송 with CSMA/CD
     */
    public boolean ethernetTransmit(byte[] frameData) throws InterruptedException {
        int collisionCount = 0;

        while (collisionCount < MAX_COLLISIONS) {
            // 1. Carrier Sense
            if (isChannelBusy()) {
                waitUntilIdle();
            }

            // 2. 전송 시작
            transmitSignal(frameData);

            // 3. Collision Detection
            if (detectCollision()) {
                collisionCount++;

                // Jam Signal 전송
                sendJamSignal();

                // 4. Binary Exponential Backoff
                int k = Math.min(collisionCount, 10);
                int maxSlots = (1 << k) - 1;  // 2^k - 1
                int randomSlots = random.nextInt(maxSlots + 1);
                double backoffTime = randomSlots * SLOT_TIME;

                System.out.printf("Collision %d: Wait %d slots (%.1fμs)%n",
                    collisionCount, randomSlots, backoffTime * 1_000_000);

                TimeUnit.NANOSECONDS.sleep((long)(backoffTime * 1_000_000_000));
            } else {
                System.out.printf("Transmission successful after %d collisions!%n",
                    collisionCount);
                return true;
            }
        }

        System.out.println("Frame dropped after 16 collisions");
        return false;
    }

    private boolean isChannelBusy() {
        // 실제로는 물리 계층에서 전기 신호 감지
        return false;
    }

    private boolean detectCollision() {
        // 실제로는 송신 신호와 수신 신호 비교
        return random.nextDouble() < 0.3;  // 30% 확률로 충돌
    }

    private void transmitSignal(byte[] data) {
        // 신호 전송
    }

    private void sendJamSignal() {
        // Jam Signal 전송 (충돌 알림)
    }

    private void waitUntilIdle() throws InterruptedException {
        // 매체가 idle 상태가 될 때까지 대기
    }

    public static void main(String[] args) throws InterruptedException {
        EthernetTransmitter transmitter = new EthernetTransmitter();
        transmitter.ethernetTransmit(new byte[]{0x01, 0x02, 0x03});
    }
}
```

### 실행 예시

```
Collision 1: Wait 0 slots (0.0μs)
Collision 2: Wait 2 slots (102.4μs)
Collision 3: Wait 5 slots (256.0μs)
Collision 4: Wait 12 slots (614.4μs)
Transmission successful after 4 collisions!
```

### 왜 "Binary"인가?

**선택 범위가 2의 거듭제곱으로 증가하기 때문:**

```
1회: 0 ~ 1    (2^1 - 1)
2회: 0 ~ 3    (2^2 - 1)
3회: 0 ~ 7    (2^3 - 1)
4회: 0 ~ 15   (2^4 - 1)
```

### 왜 최대 10까지만?

```
10회 이후: 슬롯 범위가 0 ~ 1023으로 고정
이유: 지나치게 긴 대기 시간 방지
최대 대기: 52.4ms (1023 슬롯)
```

### 16회 후 포기하는 이유

```
16회 연속 충돌 = 매우 심각한 네트워크 혼잡
확률: (충돌 확률)^16 ≈ 매우 낮음
조치: Frame 손실 → 상위 계층(TCP 등)이 재전송
```

---

## 3. TCP - Retransmission Timeout (RTO)

### 개요

**TCP에서 패킷 손실 시 재전송 대기 시간을 결정하는 알고리즘**

- **프로토콜:** TCP (Transmission Control Protocol)
- **계층:** Transport Layer (Layer 4)
- **목적:** 네트워크 혼잡 시 재전송 간격 조정

### TCP 재전송 메커니즘

```
송신자                               수신자
  |                                     |
  |------ Segment (seq=100) ---------->| (손실)
  |                                     |
  |<------- (ACK 대기) ---------------|
  |       [RTO 타임아웃]                |
  |                                     |
  |------ Segment (seq=100) ---------->| (재전송)
  |                                     |
  |<------- ACK (ack=101) -------------|
  |                                     |
```

### RTO 계산 (RFC 6298)

#### 1. 초기 RTO

```
초기 RTO = 1초 (RFC 권장)
```

#### 2. RTT 측정

```java
// Round-Trip Time (RTT) 측정
public double measureRtt() {
    long sendTime = System.currentTimeMillis();
    sendPacket();
    waitForAck();
    long receiveTime = System.currentTimeMillis();

    double rttSample = (receiveTime - sendTime) / 1000.0;  // 초 단위
    return rttSample;
}
```

#### 3. SRTT (Smoothed RTT) 계산

```java
// 첫 번째 RTT 측정
SRTT = RTT_sample;

// 이후 측정
double ALPHA = 0.125;  // 1/8 (RFC 권장)
SRTT = (1 - ALPHA) * SRTT + ALPHA * RTT_sample;
```

#### 4. RTTVAR (RTT Variance) 계산

```java
// 첫 번째
RTTVAR = RTT_sample / 2;

// 이후
double BETA = 0.25;  // 1/4 (RFC 권장)
RTTVAR = (1 - BETA) * RTTVAR + BETA * Math.abs(SRTT - RTT_sample);
```

#### 5. RTO 계산

```java
double G = 0.01;  // clock granularity (예: 10ms)
RTO = SRTT + Math.max(G, 4 * RTTVAR);

// 최소/최대값 제한
RTO = Math.max(1.0, Math.min(RTO, 60.0));
```

### Exponential Backoff in TCP

**재전송 실패 시 RTO를 2배씩 증가:**

```java
public boolean tcpRetransmitWithBackoff(byte[] segment, int maxRetries)
        throws InterruptedException {
    double rto = calculateRto();  // 초기 RTO 계산

    for (int attempt = 0; attempt < maxRetries; attempt++) {
        send(segment);

        if (waitForAck((long)(rto * 1000))) {
            return true;
        }

        // Exponential Backoff
        rto = Math.min(rto * 2, 64);  // 2배 증가, 최대 64초

        System.out.printf("Retransmission %d, RTO: %.1fs%n", attempt + 1, rto);
    }

    // 최대 재시도 초과 → 연결 종료
    closeConnection();
    return false;
}
```

### 실행 예시

```
Initial RTO: 1.5초 (SRTT 기반 계산)

1회 재전송: RTO = 1.5초
2회 재전송: RTO = 3초
3회 재전송: RTO = 6초
4회 재전송: RTO = 12초
5회 재전송: RTO = 24초
6회 재전송: RTO = 48초
7회 재전송: RTO = 64초 (최대값)
8회 재전송: RTO = 64초
...
15회 재전송 후 연결 종료
```

### TCP vs Ethernet 차이점

| 항목 | Ethernet CSMA/CD | TCP RTO |
|------|------------------|---------|
| **대기 시간 계산** | 랜덤 (0 ~ 2^n-1) | 고정 2배 |
| **초기값** | 51.2μs | 1초 (RTT 기반) |
| **최대값** | 52.4ms | 64초 |
| **재시도 횟수** | 16회 | ~15회 |
| **적응성** | 없음 (고정 공식) | 있음 (RTT 측정) |

### Karn's Algorithm

**재전송된 패킷의 ACK는 RTT 계산에 사용하지 않음**

```
문제:
  |---- Segment (seq=100) ---->| (손실)
  |---- Segment (seq=100) ---->| (재전송)
  |<------- ACK (ack=101) -----|

  → 이 ACK가 원본에 대한 것인지, 재전송에 대한 것인지 모호함

해결:
  재전송된 패킷의 ACK는 RTT 측정에서 제외
```

### 구현 예시 (Java)

```java
import java.util.Random;

public class TCPRetransmission {
    private Double srtt = null;
    private Double rttvar = null;
    private double rto = 1.0;  // 초기 RTO: 1초
    private final double maxRto = 64.0;
    private final double alpha = 0.125;  // 1/8
    private final double beta = 0.25;    // 1/4
    private final Random random = new Random();

    /**
     * RTT 측정값으로 RTO 업데이트
     */
    public void updateRto(double rttSample) {
        if (srtt == null) {
            // 첫 번째 측정
            srtt = rttSample;
            rttvar = rttSample / 2;
        } else {
            // 이후 측정
            rttvar = (1 - beta) * rttvar + beta * Math.abs(srtt - rttSample);
            srtt = (1 - alpha) * srtt + alpha * rttSample;
        }

        // RTO 계산
        rto = srtt + Math.max(0.01, 4 * rttvar);
        rto = Math.max(1.0, Math.min(rto, maxRto));
    }

    /**
     * 세그먼트 전송 with Exponential Backoff
     */
    public boolean sendWithRetransmit(String segment) throws InterruptedException {
        double currentRto = rto;

        for (int attempt = 0; attempt < 15; attempt++) {
            long sendTime = System.currentTimeMillis();

            if (sendSegment(segment)) {
                // ACK 수신 성공
                double rtt = (System.currentTimeMillis() - sendTime) / 1000.0;
                updateRto(rtt);
                System.out.printf("Success! RTT: %.3fs, New RTO: %.3fs%n", rtt, rto);
                return true;
            }

            // 타임아웃 → 재전송
            System.out.printf("Timeout %d, RTO: %.1fs%n", attempt + 1, currentRto);

            // Exponential Backoff
            currentRto = Math.min(currentRto * 2, maxRto);
        }

        System.out.println("Connection closed after 15 retransmissions");
        return false;
    }

    /**
     * 세그먼트 전송 시뮬레이션
     */
    private boolean sendSegment(String segment) {
        // 70% 확률로 성공
        return random.nextDouble() < 0.7;
    }

    public static void main(String[] args) throws InterruptedException {
        TCPRetransmission tcp = new TCPRetransmission();
        tcp.sendWithRetransmit("DATA");
    }
}
```

---

## 4. Wi-Fi (802.11) - Contention Window

### 개요

**Wi-Fi에서 충돌 발생 시 재전송 대기 시간을 결정하는 알고리즘**

- **프로토콜:** IEEE 802.11 (Wi-Fi)
- **계층:** MAC (Medium Access Control)
- **메커니즘:** DCF (Distributed Coordination Function)

### DCF (CSMA/CA) 동작

```
1. Carrier Sense: 채널이 DIFS 동안 idle인지 확인
2. Backoff: Random backoff time 대기
3. 전송 시도
4. ACK 대기
   └─ ACK 수신 → 성공
   └─ ACK 없음 → 충돌로 간주 → CW 증가
```

### Contention Window (CW)

**802.11의 Exponential Backoff 메커니즘**

#### 기본 파라미터 (802.11b 기준)

```
CWmin = 31 (2^5 - 1)
CWmax = 1023 (2^10 - 1)
Slot Time = 20μs
```

#### CW 증가 규칙

```
초기: CW = CWmin = 31
1회 충돌: CW = 2 × CWmin + 1 = 63
2회 충돌: CW = 2 × 63 + 1 = 127
3회 충돌: CW = 2 × 127 + 1 = 255
4회 충돌: CW = 2 × 255 + 1 = 511
5회 충돌: CW = 2 × 511 + 1 = 1023 (최대값)
6회 이후: CW = 1023 (고정)
```

#### Backoff Time 계산

```java
int backoffSlots = random.nextInt(CW + 1);
double backoffTime = backoffSlots * slotTime;

// 예: CW=31, 슬롯=15 선택
backoffTime = 15 × 20μs = 300μs
```

### 상세 표

```
재전송   | CW 값  | 슬롯 범위  | 최대 대기 시간
--------|--------|-----------|------------------
0회     | 31     | 0 ~ 31    | 620μs
1회     | 63     | 0 ~ 63    | 1.26ms
2회     | 127    | 0 ~ 127   | 2.54ms
3회     | 255    | 0 ~ 255   | 5.10ms
4회     | 511    | 0 ~ 511   | 10.22ms
5회+    | 1023   | 0 ~ 1023  | 20.46ms
```

### 구현 예시 (Java)

```java
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class WiFiBackoff {
    private final int cwMin = 31;
    private final int cwMax = 1023;
    private final double slotTime = 0.00002;  // 20μs
    private final int maxRetries = 7;
    private final Random random = new Random();

    /**
     * Wi-Fi 프레임 전송 with CSMA/CA
     */
    public boolean transmitFrame(byte[] frameData) throws InterruptedException {
        int cw = cwMin;

        for (int retry = 0; retry < maxRetries; retry++) {
            // 1. DIFS 대기 (DCF Interframe Space)
            TimeUnit.MICROSECONDS.sleep(50);  // 50μs (DIFS)

            // 2. Random Backoff
            int backoffSlots = random.nextInt(cw + 1);
            double backoffTime = backoffSlots * slotTime;

            System.out.printf("Retry %d: CW=%d, Backoff=%d slots (%.2fms)%n",
                retry, cw, backoffSlots, backoffTime * 1000);

            TimeUnit.NANOSECONDS.sleep((long)(backoffTime * 1_000_000_000));

            // 3. 전송 시도
            if (sendFrame(frameData)) {
                System.out.println("Frame transmitted successfully!");
                return true;
            }

            // 4. ACK 타임아웃 → CW 증가
            cw = Math.min(2 * cw + 1, cwMax);
        }

        System.out.println("Frame dropped after 7 retries");
        return false;
    }

    /**
     * 프레임 전송 시뮬레이션
     */
    private boolean sendFrame(byte[] data) {
        // 70% 확률로 성공 (ACK 수신)
        return random.nextDouble() < 0.7;
    }

    public static void main(String[] args) throws InterruptedException {
        WiFiBackoff wifi = new WiFiBackoff();
        wifi.transmitFrame("Hello WiFi".getBytes());
    }
}
```

### 실행 예시

```
Retry 0: CW=31, Backoff=23 slots (0.46ms)
Retry 1: CW=63, Backoff=42 slots (0.84ms)
Retry 2: CW=127, Backoff=88 slots (1.76ms)
Frame transmitted successfully!
```

### Wi-Fi vs Ethernet 차이점

| 항목 | Ethernet CSMA/CD | Wi-Fi CSMA/CA |
|------|------------------|---------------|
| **충돌 감지** | 전송 중 감지 | ACK 타임아웃으로 감지 |
| **초기 CW** | 0~1 슬롯 | 0~31 슬롯 |
| **최대 CW** | 0~1023 슬롯 | 0~1023 슬롯 |
| **슬롯 시간** | 51.2μs | 20μs (802.11b) |
| **재시도** | 16회 | 7회 (짧은 프레임) |

### QoS와 CW

**802.11e에서는 트래픽 우선순위별로 다른 CW 사용:**

```
AC (Access Category) | CWmin | CWmax | 용도
---------------------|-------|-------|------------
AC_VO (Voice)        | 3     | 7     | 음성 통화
AC_VI (Video)        | 7     | 15    | 비디오 스트리밍
AC_BE (Best Effort)  | 15    | 1023  | 일반 데이터
AC_BK (Background)   | 15    | 1023  | 백업, 다운로드
```

**음성 통화가 일반 데이터보다 빨리 전송됨 (작은 CW)**

---

## 5. HTTP API - Exponential Backoff + Jitter

### 개요

**HTTP API 호출 실패 시 재시도 알고리즘**

- **계층:** Application Layer (Layer 7)
- **사용 사례:** REST API, 클라우드 서비스 (AWS, GCP, Azure)
- **목적:** API Rate Limiting, 서버 과부하 방지

### 기본 Exponential Backoff

```java
import java.net.http.*;
import java.net.URI;
import java.io.IOException;

public class APIClient {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * 기본 Exponential Backoff
     */
    public String apiCallWithBackoff(String url, int maxRetries)
            throws IOException, InterruptedException {
        double baseDelay = 1.0;  // 1초

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return response.body();
                }

                // 5xx 에러 → 재시도
                if (response.statusCode() >= 500) {
                    double delay = baseDelay * Math.pow(2, attempt);
                    System.out.printf("Retry %d: %.1fs delay%n", attempt + 1, delay);
                    Thread.sleep((long)(delay * 1000));
                } else {
                    // 4xx 에러 → 재시도 불필요
                    return null;
                }

            } catch (IOException | InterruptedException e) {
                double delay = baseDelay * Math.pow(2, attempt);
                System.out.printf("Exception %d: %.1fs delay%n", attempt + 1, delay);
                Thread.sleep((long)(delay * 1000));
            }
        }

        return null;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        APIClient client = new APIClient();
        String result = client.apiCallWithBackoff("https://api.example.com/data", 5);
    }
}
```

**대기 시간:**
```
1회: 1초
2회: 2초
3회: 4초
4회: 8초
5회: 16초
```

### 문제: Thundering Herd

**여러 클라이언트가 동시에 재시도하면 서버 과부하:**

```
시간    | 클라이언트 A | 클라이언트 B | 클라이언트 C
--------|-------------|-------------|-------------
00:00   | 요청 → 503  | 요청 → 503  | 요청 → 503
00:01   | 재시도      | 재시도      | 재시도      ← 동시 재시도!
00:02   | 실패        | 실패        | 실패
00:03   | 재시도      | 재시도      | 재시도      ← 또 동시!
```

### 해결: Jitter 추가

**재시도 시간에 랜덤 값을 더해 분산:**

#### Full Jitter (AWS 권장)

```java
import java.util.Random;

public class BackoffStrategy {
    private final Random random = new Random();

    /**
     * Full Jitter: 0 ~ (base * 2^attempt) 중 랜덤
     */
    public double exponentialBackoffFullJitter(int attempt, double baseDelay, double maxDelay) {
        double exponentialDelay = baseDelay * Math.pow(2, attempt);
        double cappedDelay = Math.min(exponentialDelay, maxDelay);

        // 0 ~ cappedDelay 중 랜덤 선택
        return random.nextDouble() * cappedDelay;
    }

    // 실행 예시:
    // attempt=0: 0 ~ 1초
    // attempt=1: 0 ~ 2초
    // attempt=2: 0 ~ 4초
    // attempt=3: 0 ~ 8초
}
```

#### Equal Jitter

```java
/**
 * Equal Jitter: (base * 2^attempt / 2) + 랜덤
 */
public double exponentialBackoffEqualJitter(int attempt, double baseDelay, double maxDelay) {
    double exponentialDelay = baseDelay * Math.pow(2, attempt);
    double cappedDelay = Math.min(exponentialDelay, maxDelay);

    // 절반은 고정, 절반은 랜덤
    double baseSleep = cappedDelay / 2;
    double jitter = random.nextDouble() * (cappedDelay / 2);

    return baseSleep + jitter;
}

// 실행 예시:
// attempt=0: 0.5 ~ 1초
// attempt=1: 1 ~ 2초
// attempt=2: 2 ~ 4초
// attempt=3: 4 ~ 8초
```

#### Decorrelated Jitter

```java
/**
 * Decorrelated Jitter: 이전 대기 시간 기반
 */
public class DecorrelatedJitter {
    private final Random random = new Random();
    private double sleep;
    private final double baseDelay;
    private final double maxDelay;

    public DecorrelatedJitter(double baseDelay, double maxDelay) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.sleep = baseDelay;
    }

    public double nextDelay() {
        double currentSleep = sleep;

        // 다음 대기: [base, sleep * 3] 중 랜덤
        sleep = Math.min(maxDelay,
            baseDelay + random.nextDouble() * (sleep * 3 - baseDelay));

        return currentSleep;
    }
}

// 실행 예시:
// 1회: 1초
// 2회: 1 ~ 3초 중 랜덤 (예: 2.1초)
// 3회: 1 ~ 6.3초 중 랜덤 (예: 4.5초)
// 4회: 1 ~ 13.5초 중 랜덤
```

### AWS SDK 구현

```java
import java.util.Random;

public class AWSAPIClient {
    private final int maxRetries = 5;
    private final double baseDelay = 1.0;
    private final double maxDelay = 20.0;
    private final Random random = new Random();

    /**
     * AWS API 호출 with Exponential Backoff + Full Jitter
     */
    public <T> T callApiWithBackoff(APIFunction<T> apiFunction)
            throws Exception {

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                return apiFunction.call();

            } catch (ThrottlingException e) {
                if (attempt == maxRetries - 1) {
                    throw e;
                }

                // Full Jitter
                double exponentialDelay = baseDelay * Math.pow(2, attempt);
                double maxSleep = Math.min(exponentialDelay, maxDelay);
                double sleepTime = random.nextDouble() * maxSleep;

                System.out.printf("Throttled, retry %d after %.2fs%n",
                    attempt + 1, sleepTime);
                Thread.sleep((long)(sleepTime * 1000));

            } catch (Exception e) {
                // 다른 에러는 재시도 안 함
                throw e;
            }
        }

        throw new Exception("Max retries exceeded");
    }

    @FunctionalInterface
    interface APIFunction<T> {
        T call() throws Exception;
    }

    static class ThrottlingException extends Exception {
        public ThrottlingException(String message) {
            super(message);
        }
    }
}
```

### Jitter 비교

```
상황: 3번째 재시도 (base_delay=1초)

No Jitter:
  모든 클라이언트: 정확히 4초 대기

Full Jitter:
  클라이언트 A: 0.8초
  클라이언트 B: 2.3초
  클라이언트 C: 3.5초
  → 분산됨!

Equal Jitter:
  클라이언트 A: 2.1초
  클라이언트 B: 2.8초
  클라이언트 C: 3.4초
  → 2~4초 범위에서 분산
```

### HTTP Status Code별 전략

```java
public class SmartRetryStrategy {
    private final BackoffStrategy backoffStrategy = new BackoffStrategy();

    /**
     * HTTP 상태 코드별 재시도 전략
     */
    public Double getRetryDelay(HttpResponse<?> response, int attempt) {
        int statusCode = response.statusCode();

        // 2xx: 성공 → 재시도 불필요
        if (statusCode >= 200 && statusCode < 300) {
            return null;
        }

        // 4xx: 클라이언트 에러 → 재시도 불필요 (401, 403, 404 등)
        if (statusCode >= 400 && statusCode < 500) {
            // 예외: 429 Too Many Requests
            if (statusCode == 429) {
                // Retry-After 헤더 확인
                String retryAfter = response.headers()
                    .firstValue("Retry-After")
                    .orElse(null);

                if (retryAfter != null) {
                    return Double.parseDouble(retryAfter);
                } else {
                    return backoffStrategy.exponentialBackoffFullJitter(
                        attempt, 1.0, 60.0);
                }
            }
            return null;
        }

        // 5xx: 서버 에러 → 재시도
        if (statusCode >= 500 && statusCode < 600) {
            return backoffStrategy.exponentialBackoffFullJitter(
                attempt, 1.0, 60.0);
        }

        return null;
    }
}
```

---

## 6. DNS - Query Retry

### 개요

**DNS 쿼리 실패 시 재시도 알고리즘**

- **프로토콜:** DNS (Domain Name System)
- **계층:** Application Layer (Layer 7)
- **포트:** UDP 53 (기본), TCP 53 (fallback)

### DNS Resolver 동작

```
클라이언트                DNS Resolver              Root/TLD/Auth NS
    |                          |                            |
    |-- Query: example.com --->|                            |
    |                          |--- Query: example.com ---->|
    |                          |                            |
    |                          |<-- Response (타임아웃) ---|
    |                          |                            |
    |                          |--- Retry (다른 NS) ------->|
    |                          |<-- Response ---------------|
    |                          |                            |
    |<-- Response: IP address -|                            |
```

### DNS Timeout & Retry 설정

#### Linux resolv.conf

```bash
# /etc/resolv.conf

nameserver 8.8.8.8
nameserver 8.8.4.4

options timeout:2    # 첫 타임아웃: 2초
options attempts:3   # 최대 재시도: 3회
```

#### DNS Query Retry 알고리즘

```
쿼리 1: NS1으로 전송 → 2초 대기
쿼리 2: NS2로 전송 → 4초 대기 (2배)
쿼리 3: NS1으로 전송 → 8초 대기 (2배)
쿼리 4: NS2로 전송 → 8초 대기 (최대값)
```

### 구현 예시 (Java)

```java
import java.net.*;
import java.util.*;

public class DNSResolver {
    private final List<String> nameservers;
    private final double initialTimeout;
    private final int maxRetries;
    private final double maxTimeout = 8.0;
    private final Random random = new Random();

    public DNSResolver(List<String> nameservers, double timeout, int maxRetries) {
        this.nameservers = nameservers;
        this.initialTimeout = timeout;
        this.maxRetries = maxRetries;
    }

    /**
     * DNS 쿼리 with Exponential Backoff
     */
    public String resolve(String domain) throws Exception {
        double timeout = initialTimeout;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            // Round-robin으로 네임서버 선택
            String ns = nameservers.get(attempt % nameservers.size());

            System.out.printf("Query %d: %s, timeout=%.1fs%n",
                attempt + 1, ns, timeout);

            try {
                // DNS 쿼리 전송
                String result = queryDns(domain, ns, timeout);
                System.out.printf("Resolved: %s → %s%n", domain, result);
                return result;

            } catch (SocketTimeoutException e) {
                System.out.printf("Timeout after %.1fs%n", timeout);

                // Exponential Backoff
                timeout = Math.min(timeout * 2, maxTimeout);

            } catch (Exception e) {
                System.out.printf("Error: %s%n", e.getMessage());
                timeout = Math.min(timeout * 2, maxTimeout);
            }
        }

        throw new Exception(
            String.format("Failed to resolve %s after %d attempts",
                domain, maxRetries));
    }

    /**
     * DNS 쿼리 전송 (시뮬레이션)
     */
    private String queryDns(String domain, String nameserver, double timeout)
            throws Exception {
        // 실제로는 DNS 프로토콜로 쿼리
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int)(timeout * 1000));

            // 시뮬레이션: 50% 확률로 타임아웃
            if (random.nextDouble() < 0.5) {
                Thread.sleep((long)((timeout + 0.1) * 1000));  // 타임아웃 유발
            }

            // 실제로는 InetAddress.getByName() 사용
            InetAddress address = InetAddress.getByName(domain);
            return address.getHostAddress();
        }
    }

    public static void main(String[] args) {
        DNSResolver resolver = new DNSResolver(
            Arrays.asList("8.8.8.8", "8.8.4.4", "1.1.1.1"),
            2.0,
            4
        );

        try {
            String ip = resolver.resolve("example.com");
            System.out.printf("IP: %s%n", ip);
        } catch (Exception e) {
            System.out.printf("Resolution failed: %s%n", e.getMessage());
        }
    }
}
```

### 실행 예시

```
Query 1: 8.8.8.8, timeout=2.0s
Timeout after 2.0s
Query 2: 8.8.4.4, timeout=4.0s
Timeout after 4.0s
Query 3: 1.1.1.1, timeout=8.0s
Resolved: example.com → 93.184.216.34
IP: 93.184.216.34
```

### DNS over HTTPS (DoH)

**HTTPS 기반 DNS는 HTTP Retry 전략 사용:**

```java
import java.net.http.*;
import java.net.URI;
import org.json.*;

public class DNSoverHTTPS {
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * DNS over HTTPS 쿼리
     */
    public String dohQuery(String domain) throws Exception {
        String dohServer = "https://cloudflare-dns.com/dns-query";

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String url = String.format("%s?name=%s&type=A", dohServer, domain);

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/dns-json")
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();

                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JSONObject data = new JSONObject(response.body());
                    JSONArray answers = data.getJSONArray("Answer");
                    if (answers.length() > 0) {
                        return answers.getJSONObject(0).getString("data");
                    }
                }

                // Exponential Backoff
                long delay = (long)Math.pow(2, attempt) * 1000;
                Thread.sleep(delay);

            } catch (Exception e) {
                long delay = (long)Math.pow(2, attempt) * 1000;
                Thread.sleep(delay);
            }
        }

        return null;
    }

    public static void main(String[] args) throws Exception {
        DNSoverHTTPS doh = new DNSoverHTTPS();
        String ip = doh.dohQuery("example.com");
        System.out.printf("IP: %s%n", ip);
    }
}
```

### DNS Caching

**재시도를 줄이기 위한 캐싱 전략:**

```java
import java.time.*;
import java.util.*;

public class DNSCache {
    private final Map<String, CacheEntry> cache = new HashMap<>();

    static class CacheEntry {
        final String ip;
        final Instant expiry;

        CacheEntry(String ip, Instant expiry) {
            this.ip = ip;
            this.expiry = expiry;
        }
    }

    /**
     * 캐시에서 조회
     */
    public String get(String domain) {
        CacheEntry entry = cache.get(domain);
        if (entry != null) {
            if (Instant.now().isBefore(entry.expiry)) {
                return entry.ip;
            } else {
                cache.remove(domain);
            }
        }
        return null;
    }

    /**
     * 캐시에 저장 (TTL: 5분)
     */
    public void set(String domain, String ip, long ttlSeconds) {
        Instant expiry = Instant.now().plusSeconds(ttlSeconds);
        cache.put(domain, new CacheEntry(ip, expiry));
    }

    /**
     * 캐시와 함께 DNS 조회
     */
    public String resolveWithCache(DNSResolver resolver, String domain)
            throws Exception {
        // 1. 캐시 확인
        String ip = get(domain);
        if (ip != null) {
            System.out.printf("Cache hit: %s → %s%n", domain, ip);
            return ip;
        }

        // 2. DNS 쿼리 (재시도 포함)
        ip = resolver.resolve(domain);

        // 3. 캐시 저장
        set(domain, ip, 300);  // 5분

        return ip;
    }
}
```

---

## 7. 알고리즘 비교 및 선택 가이드

### 전체 비교표

| 알고리즘 | 계층 | 초기 지연 | 증가 방식 | 최대 지연 | 재시도 | 랜덤 |
|---------|------|----------|----------|----------|--------|------|
| **Ethernet CSMA/CD** | L2 | 51.2μs | 2^n - 1 슬롯 | 52.4ms | 16회 | ✅ |
| **TCP RTO** | L4 | 1초 (RTT) | 2배 | 64초 | ~15회 | ❌ |
| **Wi-Fi CW** | L2 | 620μs | 2배 + 1 | 20.46ms | 7회 | ✅ |
| **HTTP API** | L7 | 1초 | 2배 | 60초 | 5~10회 | ✅ (Jitter) |
| **DNS** | L7 | 2초 | 2배 | 8초 | 3~4회 | ❌ |

### 특징별 비교

#### 1. 대기 시간 범위

```
빠름 ←────────────────────────────────────→ 느림

Ethernet     Wi-Fi        TCP         HTTP API      DNS
51.2μs      620μs         1초          1초          2초
```

#### 2. 랜덤성

```
결정적 ←──────────────────────────────────→ 랜덤

TCP          DNS      HTTP (No Jitter)    Wi-Fi    Ethernet
고정 2배      고정     고정 2배         랜덤 범위   랜덤 범위
                                      + Full Jitter
```

#### 3. 적응성

```
고정 ←────────────────────────────────────→ 적응

Ethernet   Wi-Fi    DNS   HTTP(basic)   TCP(RTT)   HTTP(Jitter)
고정       고정     고정    고정          RTT 측정    동적 Jitter
공식       공식     공식    공식          기반        추가
```

### 사용 시나리오별 선택 가이드

#### 1. 물리 계층 충돌 (공유 매체)
```
추천: Binary Exponential Backoff (Ethernet, Wi-Fi)
이유: 다수 장치의 동시 접근, 빠른 충돌 해결 필요
특징: 랜덤 선택으로 충돌 확률 급격히 감소
```

#### 2. 네트워크 패킷 손실
```
추천: TCP RTO (RTT 기반 적응)
이유: 네트워크 상태에 따라 RTT 변동
특징: 측정된 RTT로 최적 타임아웃 계산
```

#### 3. API 호출 (Rate Limiting)
```
추천: Exponential Backoff + Full Jitter
이유: 다수 클라이언트의 동시 재시도 방지
특징: Jitter로 Thundering Herd 회피
```

#### 4. 데이터베이스 연결
```
추천: Exponential Backoff + Equal Jitter
이유: 서버 과부하 시 분산된 재연결
특징: 최소 대기 시간 보장 + 랜덤 분산
```

#### 5. 파일 시스템 Lock
```
추천: Exponential Backoff (고정 증가)
이유: 로컬 시스템, 네트워크 지연 없음
특징: 단순하고 예측 가능
```

### Jitter 선택 가이드

```java
// 시나리오별 Jitter 전략

// 1. 고가용성 필요 (최소 지연 보장)
// → Equal Jitter
public double equalJitter(double base, int attempt) {
    double delay = base * Math.pow(2, attempt);
    return delay / 2 + random.nextDouble() * (delay / 2);
}
// 예: 50% 고정 + 50% 랜덤

// 2. 최대 분산 필요 (Thundering Herd 방지)
// → Full Jitter
public double fullJitter(double base, int attempt) {
    double delay = base * Math.pow(2, attempt);
    return random.nextDouble() * delay;
}
// 예: 0% ~ 100% 랜덤

// 3. 점진적 증가 (이전 시도 기반)
// → Decorrelated Jitter
public double decorrelatedJitter(double base, double prevSleep) {
    return base + random.nextDouble() * (prevSleep * 3 - base);
}
// 예: 이전 대기 시간의 3배까지
```

### 실전 구현 팁

#### 1. 최대 재시도 제한

```java
// 무한 재시도 방지
final int MAX_RETRIES = 5;

// 총 대기 시간 제한
final long MAX_TOTAL_WAIT = 60000;  // 60초 (밀리초)

long totalWait = 0;
for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
    long delay = calculateBackoff(attempt);

    if (totalWait + delay > MAX_TOTAL_WAIT) {
        break;
    }

    Thread.sleep(delay);
    totalWait += delay;
}
```

#### 2. 에러 타입별 전략

```java
public <T> T retryWithErrorHandling(Callable<T> func, int maxRetries)
        throws Exception {
    for (int attempt = 0; attempt < maxRetries; attempt++) {
        try {
            return func.call();
        } catch (TransientException e) {
            // 일시적 에러 → 재시도
            long delay = calculateBackoff(attempt);
            Thread.sleep(delay);
        } catch (PermanentException e) {
            // 영구적 에러 → 즉시 실패
            throw e;
        }
    }
    throw new Exception("Max retries exceeded");
}

class TransientException extends Exception {}
class PermanentException extends Exception {}
```

#### 3. 모니터링 및 로깅

```java
import java.util.logging.*;

public class RetryLogger {
    private static final Logger logger = Logger.getLogger(RetryLogger.class.getName());

    public <T> T retryWithLogging(Callable<T> func, int maxRetries)
            throws Exception {
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                T result = func.call();

                if (attempt > 0) {
                    logger.info(String.format("Success after %d retries", attempt));
                }

                return result;
            } catch (Exception e) {
                long delay = calculateBackoff(attempt);
                logger.warning(String.format(
                    "Attempt %d failed: %s, retrying in %dms",
                    attempt + 1, e.getMessage(), delay));
                Thread.sleep(delay);
            }
        }
        throw new Exception("Max retries exceeded");
    }

    private long calculateBackoff(int attempt) {
        return (long)(1000 * Math.pow(2, attempt));
    }
}
```

---

## 참고 자료

### 표준 문서
- **RFC 6298**: Computing TCP's Retransmission Timer
- **IEEE 802.3**: Ethernet Standard (CSMA/CD)
- **IEEE 802.11**: Wi-Fi Standard (CSMA/CA)
- **RFC 1035**: Domain Names - Implementation and Specification

### 추천 읽을거리
- [AWS Architecture Blog - Exponential Backoff And Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
- [Google Cloud - Retry Strategy Best Practices](https://cloud.google.com/iot/docs/how-tos/exponential-backoff)
- [Computer Networks: A Top-Down Approach](https://gaia.cs.umass.edu/kurose_ross/) (Kurose & Ross)

### 라이브러리
- **Python**: `tenacity`, `backoff`, `requests` (내장 재시도)
- **Java**: `Resilience4j`, `Spring Retry`
- **Go**: `github.com/cenkalti/backoff`
- **JavaScript**: `exponential-backoff` (npm)

---

## 요약

### Exponential Backoff의 핵심 원칙

1. **지수적 증가**: 실패할수록 대기 시간을 2배씩 증가
2. **최대값 제한**: 무한정 증가하지 않도록 상한선 설정
3. **랜덤 분산**: Jitter로 동시 재시도 방지
4. **빠른 포기**: 일정 횟수 실패 시 조기 종료

### 계층별 적용

```
Application (L7)
  └─ HTTP API: Exponential + Full Jitter
  └─ DNS: Exponential (고정)

Transport (L4)
  └─ TCP: Exponential + RTT 적응

Data Link (L2)
  └─ Ethernet: Binary Exponential (랜덤)
  └─ Wi-Fi: Binary Exponential (랜덤)
```

### 선택 기준

- **다수 경쟁:** Binary Exponential (랜덤 범위)
- **네트워크 변동:** RTT 기반 적응
- **API 호출:** Jitter 추가
- **단순 재시도:** 고정 Exponential

모든 네트워크 프로토콜과 분산 시스템에서 **Exponential Backoff는 충돌과 혼잡을 해결하는 핵심 메커니즘**입니다! 🚀
