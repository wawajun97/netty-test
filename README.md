# netty-test

Spring Boot + Netty 기반의 로봇 TCP 통신 서버입니다.  
다수의 로봇으로부터 커스텀 바이너리 프로토콜 메시지를 수신하고, `robotType + opCode` 기준으로 비즈니스 로직을 분기한 뒤, ACK 응답과 DB 저장을 비동기로 처리합니다.

---

## 기술적 의사결정

### Netty를 선택한 이유

일반 Java NIO로도 non-blocking TCP 서버를 구현할 수 있지만, partial read/write, buffer 관리, selector loop를 직접 구현해야 합니다.  
Netty는 event loop, pipeline, encoder/decoder, backpressure 제어(`AUTO_READ`, write buffer watermark)를 기본 제공하므로 커스텀 바이너리 프로토콜을 더 단순하고 안정적으로 구현할 수 있어 채택했습니다.

### Spring Batch 대신 JDBC batch를 선택한 이유

이 서버의 저장 흐름은 TCP로 지속적으로 유입되는 소규모 메시지를 메모리 큐에 모아 짧은 주기로 DB에 저장하는 실시간 ingest 구조입니다.  
Spring Batch는 ETL·정산·마이그레이션처럼 job 실행 이력 관리와 실패 재시작이 필요한 경우에 적합합니다.  
이 프로젝트에서는 빠른 ACK 응답, insert 묶음 처리, backpressure 직접 제어가 더 중요하므로 `PersistenceQueue + PersistenceWorker + JDBC batch insert` 조합을 사용했습니다.

---

## 핵심 기능

- 커스텀 바이너리 TCP 프로토콜(`STX | header | payload | ETX`) 파싱
- `robotType / opCode` 기준 메시지 라우팅 및 비즈니스 로직 분기
- 공통 ACK / NACK / BUSY 응답 프레임 인코딩
- `PersistenceQueue + PersistenceWorker` 기반 비동기 JDBC batch 저장
- queue 포화 시 `AUTO_READ` 제어 기반 backpressure 대응
- Prometheus + Grafana 기반 실시간 메트릭 관측
- 전용 Load Runner 기반 부하 테스트 (burst / sustained / saturation 시나리오)

---

## 메시지 처리 흐름

```
TCP 연결 수립
  → RobotFrameDecoder       : STX/ETX 기반 프레임 파싱 → RobotFrame 변환
  → RobotMessageRouterHandler: robotType + opCode 기준 처리기 선택
  → 비즈니스 핸들러          : payload 파싱 및 검증
  → PersistenceQueue         : 저장 대상 적재
  → PersistenceWorker        : 주기적 queue drain → JDBC batch insert
  → RobotAckEncoder          : ACK/NACK/BUSY 응답 프레임 인코딩
```

---

## TCP 프로토콜

### 프레임 구조

```
STX(1) | dataSize(4) | robotType(1) | opCode(1) | payload(N) | ETX(1)
```

### 지원 메시지

| robotType | opCode | 설명 |
|-----------|--------|------|
| `0x01` (TYPE_A) | `0x01` STATUS | 상태 코드, 배터리 수신 |
| `0x01` (TYPE_A) | `0x02` POSITION | 좌표(x, y), 방향각 수신 |
| `0x02` (TYPE_B) | `0x01` STATUS | 동일 구조 |
| `0x02` (TYPE_B) | `0x02` POSITION | 동일 구조 |

---

## 실행 방법

**요구사항:** Java 17, MySQL, Docker

```bash
# 1. 애플리케이션 실행
./gradlew bootRun

# 2. 단위/통합 테스트
./gradlew test

# 3. 관측 스택 실행 (Prometheus + Grafana)
docker compose up -d

# 4. 부하 테스트 (burst 예시)
./gradlew \
  -Drobot.load.scenario=burst \
  -Drobot.load.connections=200 \
  -Drobot.load.messagesPerConnection=1000 \
  robotLoadTest
```

**주요 포트**
- HTTP: `8080`
- Robot TCP: `29001`
- Prometheus: `9090`
- Grafana: `3000` (admin / admin)

---

## 주요 관측 지표 (Grafana)

- 초당 수신 frame 수 / ACK 응답 분포
- BUSY · NACK 추이
- Persistence queue depth / batch latency
- JVM heap · GC · thread · CPU
