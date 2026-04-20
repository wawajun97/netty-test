# Load Testing

## 1. Start the application

Run the Spring Boot application so that:

- robot TCP server listens on `robot.netty.port`
- actuator metrics are exposed on `/actuator/prometheus`

## 2. Start Prometheus and Grafana

```bash
docker compose up -d
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`
- Default Grafana credentials: `admin / admin`

Prometheus scrapes `http://host.docker.internal:8080/actuator/prometheus` by default.
If the Spring Boot HTTP port differs, update `observability/prometheus/prometheus.yml`.

## 3. Run the load runner

Example burst run:

```bash
bash ./gradlew \
  -Drobot.load.scenario=burst \
  -Drobot.load.connections=200 \
  -Drobot.load.messagesPerConnection=1000 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```

Example sustained run:

```bash
bash ./gradlew \
  -Drobot.load.scenario=sustained \
  -Drobot.load.connections=500 \
  -Drobot.load.durationSeconds=120 \
  -Drobot.load.targetRate=20000 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```

Example saturation run:

```bash
bash ./gradlew \
  -Drobot.load.scenario=saturation \
  -Drobot.load.connections=500 \
  -Drobot.load.maxDurationSeconds=180 \
  -Drobot.load.initialRate=5000 \
  -Drobot.load.maxTargetRate=50000 \
  -Drobot.load.rateStep=5000 \
  -Drobot.load.stepDurationSeconds=15 \
  -Drobot.load.stop.busyRatio=0.05 \
  -Drobot.load.stop.timeoutRatio=0.01 \
  -Drobot.load.stop.errorRatio=0.01 \
  -Drobot.load.stop.p99Millis=100 \
  -Drobot.load.stop.consecutiveBreaches=2 \
  -Drobot.load.reportDir=build/reports/robot-load \
  robotLoadTest
```

## 4. Review outputs

Each run writes a timestamped report directory containing:

- `summary.json`
- `latency.csv`
- `ack-counts.csv`
- `client-errors.csv`
- `ramp-steps.csv`
- `failure-threshold.json`

Use Grafana to compare the report with:

- ACK/BUSY trends
- queue depth
- batch latency
- JVM memory / GC / threads

For `saturation`, the runner increases the target TPS step by step and stops automatically when one of the configured thresholds keeps breaching for the configured number of consecutive steps.
