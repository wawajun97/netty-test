# netty-test

Spring Boot + Netty 기반의 로봇 TCP 통신 서버입니다.  
여러 종류의 로봇 메시지를 수신하고, `robotType` + `opCode` 기준으로 비즈니스 로직을 분기한 뒤, 응답은 즉시 ACK/NACK로 돌려주고 저장은 비동기 JDBC batch로 처리합니다.

이 프로젝트는 아래 목적을 기준으로 구성되어 있습니다.

- 커스텀 바이너리 TCP 프로토콜 처리
- 기능별 핸들러 분리
- 로봇 타입별 비즈니스 로직 분리
- DB 저장 비동기화
- backpressure 대응
- Prometheus + Grafana 기반 관측
- 전용 load runner 기반 부하 테스트

## 1. 핵심 기능

- `STX | header | payload | ETX` 형태의 커스텀 TCP 프로토콜 처리
- `robotType` / `opCode` 기준 라우팅
- `STATUS`, `POSITION` 메시지 처리
- 공통 ACK/NACK/BUSY 응답 프레임 인코딩
- `PersistenceQueue` + `PersistenceWorker` 기반 비동기 배치 저장
- queue saturation 시 `AUTO_READ` 제어 기반 backpressure 대응
- Actuator + Prometheus 메트릭 노출
- `RobotLoadTestRunner` 기반 대규모 트래픽 테스트

## 2. 프로젝트 구조

주요 패키지 역할은 아래와 같습니다.

- `src/main/java/com/example/netty_test/common`
  - Netty 서버 부팅과 채널 파이프라인 초기화
- `src/main/java/com/example/netty_test/config`
  - Netty, 프로토콜, persistence 설정 바인딩
- `src/main/java/com/example/netty_test/protocol`
  - 프레임, ACK, 프로토콜 상수, 에러 코드
- `src/main/java/com/example/netty_test/handler`
  - 디코딩, 라우팅, ACK 인코딩, 채널 상태 처리
- `src/main/java/com/example/netty_test/dispatch`
  - `robotType + opCode` 기준 비즈니스 핸들러 디스패치
- `src/main/java/com/example/netty_test/robot`
  - 로봇 타입별 실제 비즈니스 로직
- `src/main/java/com/example/netty_test/application`
  - payload 파싱과 DTO
- `src/main/java/com/example/netty_test/persistence`
  - 비동기 큐, backpressure 제어, JDBC batch 저장
- `src/main/java/com/example/netty_test/metrics`
  - 수신/ACK/배치/실패 메트릭
- `src/main/java/com/example/netty_test/load`
  - TCP 부하 테스트 전용 실행기

테스트 코드는 아래에 있습니다.

- `src/test/java/com/example/netty_test/handler`
- `src/test/java/com/example/netty_test/persistence`
- `src/test/java/com/example/netty_test/load`
- `src/test/java/com/example/netty_test/observability`

## 3. 메시지 처리 흐름

현재 Netty 파이프라인은 아래 순서로 동작합니다.

1. `IdleStateHandler`
2. `RobotChannelStateHandler`
3. `RobotFrameDecoder`
4. `RobotMessageRouterHandler`
5. `RobotAckEncoder`

전체 처리 흐름은 아래와 같습니다.

1. TCP 연결 수립
2. `RobotFrameDecoder`가 `STX/ETX` 기반 프레임을 `RobotFrame`으로 변환
3. `RobotMessageRouterHandler`가 `robotType + opCode` 기준으로 처리기 선택
4. 각 비즈니스 핸들러가 payload 파싱 및 검증 수행
5. 저장 대상이면 `PersistenceQueue`에 적재
6. `PersistenceWorker`가 주기적으로 queue를 drain 해서 JDBC batch insert 수행
7. 비즈니스 결과를 `RobotAckEncoder`가 응답 프레임으로 인코딩

## 4. TCP 프로토콜

### 프레임 구조

```text
STX | dataSize | robotType | opCode | payload | ETX
```

- `STX`: 1 byte, `0x02`
- `dataSize`: 4 bytes, payload 크기
- `robotType`: 1 byte
- `opCode`: 1 byte
- `payload`: `dataSize`만큼의 데이터
- `ETX`: 1 byte, `0x03`

### 현재 지원 값

- `ROBOT_TYPE_A = 0x01`
- `ROBOT_TYPE_B = 0x02`
- `STATUS_OP_CODE = 0x01`
- `POSITION_OP_CODE = 0x02`
- `ACK_OP_CODE = 0x7F`

### payload 규격

공통 prefix:

```text
robotIdLength(2) | robotId(UTF-8) | timestampEpochMillis(8)
```

`STATUS` payload:

```text
robotIdLength | robotId | timestampEpochMillis | statusCode(1) | batteryPercent(1)
```

`POSITION` payload:

```text
robotIdLength | robotId | timestampEpochMillis | x(8 double) | y(8 double) | headingDeg(4 float)
```

## 5. 실행 전 준비사항

필수 요구사항:

- Java 17
- MySQL
- Docker / Docker Compose

기본 DB 설정은 `src/main/resources/application.properties` 기준으로 아래와 같습니다.

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/netty?&serverTimezone=Asia/Seoul
spring.datasource.username=root
spring.datasource.password=admin
```

`spring.sql.init.mode=always` 이므로 애플리케이션 시작 시 `src/main/resources/schema.sql`이 실행됩니다.

생성 테이블:

- `robot_status_history`
- `robot_position_history`
- `robot_ingest_failure`

## 6. 애플리케이션 실행 방법

### 기본 포트

- HTTP: `8080`
- Robot TCP: `29001`

### 실행

```bash
cd /Users/junseo/Projects/netty-test
bash ./gradlew bootRun
```

### 주요 확인 엔드포인트

- `GET /health`
- `GET /actuator/health`
- `GET /actuator/metrics`
- `GET /actuator/prometheus`

예시:

```bash
curl http://localhost:8080/health
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/prometheus
```

### 주요 설정값

```properties
robot.netty.port=29001
robot.netty.worker-count=10
robot.protocol.max-payload-length=65536
robot.persistence.queue-capacity=10000
robot.persistence.batch-size=200
robot.persistence.flush-interval-ms=100
robot.persistence.high-watermark=8000
robot.persistence.low-watermark=4000
```

## 7. 테스트 방법

### 단위/통합 테스트

```bash
cd /Users/junseo/Projects/netty-test
bash ./gradlew test
```

현재 테스트는 아래를 검증합니다.

- 프레임 디코딩
- 메시지 라우팅
- JDBC batch 저장
- load runner 동작
- Prometheus scrape endpoint 노출

테스트 프로파일은 `src/test/resources/application-test.properties`를 사용합니다.

- `robot.netty.enabled=false`
- H2 in-memory DB 사용
- `schema.sql` 자동 초기화

### 테스트 보고서 확인

```bash
open build/reports/tests/test/index.html
```

## 8. 부하 테스트 방법

부하 테스트는 `RobotLoadTestRunner`를 사용합니다.

실행 task:

```bash
bash ./gradlew robotLoadTest
```

### 지원 시나리오

- `burst`
- `sustained`
- `soak`
- `saturation`

### 주요 시스템 프로퍼티

- `robot.load.host`
- `robot.load.port`
- `robot.load.connections`
- `robot.load.scenario`
- `robot.load.messagesPerConnection`
- `robot.load.durationSeconds`
- `robot.load.targetRate`
- `robot.load.warmupSeconds`
- `robot.load.pauseMillis`
- `robot.load.statusRatio`
- `robot.load.robotTypeMix`
- `robot.load.payloadProfile`
- `robot.load.reportDir`
- `robot.load.runnerId`
- `robot.load.saturationStepRate`
- `robot.load.saturationStepDurationSeconds`

### burst 예시

```bash
bash ./gradlew \
  -Drobot.load.scenario=burst \
  -Drobot.load.host=127.0.0.1 \
  -Drobot.load.port=29001 \
  -Drobot.load.connections=200 \
  -Drobot.load.messagesPerConnection=1000 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```

### sustained 예시

```bash
bash ./gradlew \
  -Drobot.load.scenario=sustained \
  -Drobot.load.host=127.0.0.1 \
  -Drobot.load.port=29001 \
  -Drobot.load.connections=500 \
  -Drobot.load.durationSeconds=120 \
  -Drobot.load.targetRate=20000 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```

### saturation 예시

```bash
bash ./gradlew \
  -Drobot.load.scenario=saturation \
  -Drobot.load.host=127.0.0.1 \
  -Drobot.load.port=29001 \
  -Drobot.load.connections=500 \
  -Drobot.load.durationSeconds=180 \
  -Drobot.load.targetRate=50000 \
  -Drobot.load.saturationStepRate=5000 \
  -Drobot.load.saturationStepDurationSeconds=15 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```

### 부하 테스트 결과 파일

각 실행은 timestamp 기반 디렉토리를 만들고 아래 파일을 생성합니다.

- `summary.json`
- `latency.csv`
- `ack-counts.csv`
- `client-errors.csv`

## 9. Prometheus + Grafana 시각화

관측 스택은 repo에 함께 포함되어 있습니다.

- `docker-compose.yml`
- `observability/prometheus/prometheus.yml`
- `observability/grafana/provisioning/...`
- `observability/grafana/dashboards/robot-server.json`

### 실행

```bash
cd /Users/junseo/Projects/netty-test
docker compose up -d
```

### 접속 정보

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Grafana 계정: `admin / admin`

기본 scrape 대상은 아래입니다.

```text
http://host.docker.internal:8080/actuator/prometheus
```

HTTP 포트를 변경해서 실행했다면 `observability/prometheus/prometheus.yml`도 같이 수정해야 합니다.

### 주요 시각화 지표

- 초당 수신 frame 수
- ACK resultCode별 응답 수
- BUSY/NACK 추이
- decode failure / route miss
- persistence queue depth
- batch latency / batch size
- persistence failure
- JVM heap / GC / thread / CPU

## 10. DB 스키마 개요

`schema.sql` 기준으로 아래 테이블을 사용합니다.

### `robot_status_history`

- 상태 이력 저장
- `robot_type`, `robot_id`, `occurred_at`, `status_code`, `battery_percent`

### `robot_position_history`

- 위치 이력 저장
- `robot_type`, `robot_id`, `occurred_at`, `pos_x`, `pos_y`, `heading_deg`

### `robot_ingest_failure`

- 저장 실패 또는 ingest 실패 추적
- `robot_type`, `op_code`, `robot_id`, `error_message`

## 11. 참고 문서

- 부하 테스트 상세 가이드: [docs/load-testing.md](docs/load-testing.md)

## 12. 빠른 시작

가장 빠른 로컬 검증 순서는 아래와 같습니다.

```bash
cd /Users/junseo/Projects/netty-test

# 1. 애플리케이션 실행
bash ./gradlew bootRun

# 2. 테스트 실행
bash ./gradlew test

# 3. 시각화 스택 실행
docker compose up -d

# 4. 짧은 burst 부하 테스트
bash ./gradlew \
  -Drobot.load.scenario=burst \
  -Drobot.load.connections=20 \
  -Drobot.load.messagesPerConnection=200 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```
