package com.example.netty_test.load;

import com.example.netty_test.protocol.RobotAckResultCode;
import com.example.netty_test.protocol.RobotConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

public final class RobotLoadTestRunner {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private RobotLoadTestRunner() {
    }

    public static void main(String[] args) throws Exception {
        LoadTestConfig config = LoadTestConfig.fromSystemProperties();
        LoadTestSummary summary = execute(config);
        printSummary(summary);
    }

    static LoadTestSummary execute(LoadTestConfig config) throws InterruptedException, IOException {
        long baseNanoTime = System.nanoTime();
        long startBarrierNanos = baseNanoTime + nanosUntilBarrier(config.startBarrierEpochMillis());
        long measurementStartNanos = startBarrierNanos + TimeUnit.SECONDS.toNanos(config.warmupSeconds());
        long measurementEndNanos = config.durationSeconds() > 0
                ? measurementStartNanos + TimeUnit.SECONDS.toNanos(config.durationSeconds())
                : Long.MAX_VALUE;

        LoadTestMetrics metrics = new LoadTestMetrics(config, measurementStartNanos, measurementEndNanos);
        ExecutorService executorService = Executors.newFixedThreadPool(config.connections());
        CountDownLatch latch = new CountDownLatch(config.connections());
        Instant startedAt = Instant.now();

        for (int connectionIndex = 0; connectionIndex < config.connections(); connectionIndex++) {
            final int clientId = connectionIndex;
            executorService.submit(() -> {
                try {
                    runClient(config, metrics, clientId, startBarrierNanos);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);

        long finishedAtNanos = System.nanoTime();
        metrics.complete(finishedAtNanos);

        Instant finishedAt = Instant.now();
        LoadTestSummary summary = metrics.toSummary(config, startedAt, finishedAt, finishedAtNanos);
        writeReports(summary, metrics, config);
        return summary;
    }

    private static void runClient(
            LoadTestConfig config,
            LoadTestMetrics metrics,
            int clientId,
            long startBarrierNanos
    ) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMillis());
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(config.readTimeoutMillis());

            try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

                waitUntil(startBarrierNanos);
                long nextSendNanos = startBarrierNanos;
                int messageIndex = 0;

                while (shouldContinue(config, metrics, messageIndex)) {
                    long plannedSendNanos = nextSendNanos;
                    if (config.scenario() != LoadScenario.BURST) {
                        waitUntil(plannedSendNanos);
                    }

                    long beforeSendNanos = System.nanoTime();
                    if (!shouldContinue(config, metrics, messageIndex)) {
                        break;
                    }

                    boolean sampled = metrics.shouldSample(beforeSendNanos);
                    long targetRate = currentTargetRate(config, metrics.measurementStartNanos(), beforeSendNanos);
                    RobotMessage message = buildMessage(config, clientId, messageIndex);

                    if (sampled) {
                        metrics.recordSent(beforeSendNanos, targetRate);
                    }

                    try {
                        writeFrame(outputStream, message.robotType(), message.opCode(), message.payload());
                        LoadAck ack = readAck(inputStream);
                        if (sampled) {
                            metrics.recordAck(ack.resultCode(), beforeSendNanos, System.nanoTime(), targetRate);
                        }
                    } catch (Exception e) {
                        if (sampled) {
                            metrics.recordClientError(e.getClass().getSimpleName(), beforeSendNanos, System.nanoTime(), targetRate);
                        }
                        break;
                    }

                    messageIndex++;
                    nextSendNanos = calculateNextSendNanos(config, beforeSendNanos, plannedSendNanos, metrics);

                    if (config.pauseMillis() > 0) {
                        sleepMillis(config.pauseMillis());
                    }
                }
            }
        } catch (Exception e) {
            long now = System.nanoTime();
            if (metrics.shouldSample(now)) {
                long targetRate = currentTargetRate(config, metrics.measurementStartNanos(), now);
                metrics.recordClientError(e.getClass().getSimpleName(), now, now, targetRate);
            }
        }
    }

    private static boolean shouldContinue(LoadTestConfig config, LoadTestMetrics metrics, int messageIndex) {
        if (metrics.shouldStop()) {
            return false;
        }

        long now = System.nanoTime();
        boolean withinDuration = config.durationSeconds() <= 0 || now < metrics.measurementEndNanos();
        boolean withinMessageLimit = config.messagesPerConnection() <= 0 || messageIndex < config.messagesPerConnection();
        return withinDuration && withinMessageLimit;
    }

    private static RobotMessage buildMessage(LoadTestConfig config, int clientId, int messageIndex) {
        byte robotType = chooseRobotType(config.robotTypeMix());
        byte opCode = chooseOpCode(config.statusRatio());
        byte[] payload = opCode == RobotConstants.STATUS_OP_CODE
                ? buildStatusPayload(config.payloadProfile(), config.runnerId(), clientId, messageIndex)
                : buildPositionPayload(config.payloadProfile(), config.runnerId(), clientId, messageIndex);
        return new RobotMessage(robotType, opCode, payload);
    }

    private static long calculateNextSendNanos(
            LoadTestConfig config,
            long actualSendNanos,
            long plannedSendNanos,
            LoadTestMetrics metrics
    ) {
        if (config.scenario() == LoadScenario.BURST) {
            return actualSendNanos;
        }

        long targetRate = currentTargetRate(config, metrics.measurementStartNanos(), actualSendNanos);
        long intervalNanos = nanosPerMessage(targetRate, config.connections());
        return Math.max(actualSendNanos, plannedSendNanos) + intervalNanos;
    }

    private static long currentTargetRate(LoadTestConfig config, long measurementStartNanos, long nowNanos) {
        return switch (config.scenario()) {
            case BURST -> 0L;
            case SUSTAINED, SOAK -> Math.max(1L, config.targetRate());
            case SATURATION -> currentSaturationRate(config, measurementStartNanos, nowNanos);
        };
    }

    private static long currentSaturationRate(LoadTestConfig config, long measurementStartNanos, long nowNanos) {
        long elapsedNanos = Math.max(0L, nowNanos - measurementStartNanos);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
        long currentStep = elapsedSeconds / Math.max(1, config.stepDurationSeconds());
        long rate = config.initialRate() + (currentStep * Math.max(1, config.rateStep()));
        return Math.max(1L, Math.min(rate, Math.max(config.initialRate(), config.maxTargetRate())));
    }

    private static long nanosPerMessage(long targetRate, int connections) {
        long safeRate = Math.max(1L, targetRate);
        return Math.max(1L, (long) ((1_000_000_000d * connections) / safeRate));
    }

    private static byte chooseRobotType(RobotTypeMix mix) {
        return ThreadLocalRandom.current().nextDouble() < mix.typeARatio()
                ? RobotConstants.ROBOT_TYPE_A
                : RobotConstants.ROBOT_TYPE_B;
    }

    private static byte chooseOpCode(double statusRatio) {
        return ThreadLocalRandom.current().nextDouble() < statusRatio
                ? RobotConstants.STATUS_OP_CODE
                : RobotConstants.POSITION_OP_CODE;
    }

    private static byte[] buildStatusPayload(PayloadProfile payloadProfile, String runnerId, int clientId, int messageIndex) {
        byte[] robotIdBytes = robotId(payloadProfile, runnerId, clientId, messageIndex).getBytes(StandardCharsets.UTF_8);

        try {
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
            dataOutputStream.writeShort(robotIdBytes.length);
            dataOutputStream.write(robotIdBytes);
            dataOutputStream.writeLong(System.currentTimeMillis());
            dataOutputStream.writeByte(messageIndex % 10);
            dataOutputStream.writeByte(20 + (messageIndex % 80));
            dataOutputStream.flush();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build status payload", e);
        }
    }

    private static byte[] buildPositionPayload(PayloadProfile payloadProfile, String runnerId, int clientId, int messageIndex) {
        byte[] robotIdBytes = robotId(payloadProfile, runnerId, clientId, messageIndex).getBytes(StandardCharsets.UTF_8);

        try {
            java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
            DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
            dataOutputStream.writeShort(robotIdBytes.length);
            dataOutputStream.write(robotIdBytes);
            dataOutputStream.writeLong(System.currentTimeMillis());
            dataOutputStream.writeDouble(messageIndex * 0.5d);
            dataOutputStream.writeDouble(messageIndex * 0.75d);
            dataOutputStream.writeFloat((messageIndex * 5) % 360);
            dataOutputStream.flush();
            return byteArrayOutputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build position payload", e);
        }
    }

    private static String robotId(PayloadProfile payloadProfile, String runnerId, int clientId, int messageIndex) {
        String base = runnerId + "-robot-" + clientId + "-" + messageIndex;
        int targetLength = switch (payloadProfile) {
            case SMALL -> 16;
            case MEDIUM -> 64;
            case LARGE -> 256;
        };

        if (base.length() >= targetLength) {
            return base.substring(0, targetLength);
        }

        StringBuilder builder = new StringBuilder(base);
        while (builder.length() < targetLength) {
            builder.append('x');
        }
        return builder.toString();
    }

    private static void writeFrame(DataOutputStream outputStream, byte robotType, byte opCode, byte[] payload) throws IOException {
        outputStream.writeByte(RobotConstants.STX);
        outputStream.writeInt(payload.length);
        outputStream.writeByte(robotType);
        outputStream.writeByte(opCode);
        outputStream.write(payload);
        outputStream.writeByte(RobotConstants.ETX);
        outputStream.flush();
    }

    private static LoadAck readAck(DataInputStream inputStream) throws IOException {
        int stx = inputStream.readUnsignedByte();
        if (stx != Byte.toUnsignedInt(RobotConstants.STX)) {
            throw new EOFException("Unexpected STX: " + stx);
        }

        int dataSize = inputStream.readInt();
        int robotType = inputStream.readUnsignedByte();
        int opCode = inputStream.readUnsignedByte();
        if (opCode != Byte.toUnsignedInt(RobotConstants.ACK_OP_CODE)) {
            throw new EOFException("Unexpected ACK opcode: " + opCode);
        }

        int resultCode = inputStream.readUnsignedByte();
        int requestOpCode = inputStream.readUnsignedByte();
        int messageLength = inputStream.readUnsignedShort();
        byte[] messageBytes = inputStream.readNBytes(messageLength);
        if (messageBytes.length != messageLength) {
            throw new EOFException("Ack message truncated");
        }

        int etx = inputStream.readUnsignedByte();
        if (etx != Byte.toUnsignedInt(RobotConstants.ETX)) {
            throw new EOFException("Unexpected ETX: " + etx);
        }

        if (dataSize != 1 + 1 + 2 + messageLength) {
            throw new EOFException("Unexpected ACK size: " + dataSize);
        }

        return new LoadAck(robotType, resultCode, requestOpCode, new String(messageBytes, StandardCharsets.UTF_8));
    }

    private static void writeReports(LoadTestSummary summary, LoadTestMetrics metrics, LoadTestConfig config) throws IOException {
        Path outputDirectory = config.outputDirectory();
        Files.createDirectories(outputDirectory);

        OBJECT_MAPPER.writeValue(outputDirectory.resolve("summary.json").toFile(), summary.asJson());
        OBJECT_MAPPER.writeValue(outputDirectory.resolve("failure-threshold.json").toFile(), summary.failureThreshold().asJson());

        try (BufferedWriter writer = Files.newBufferedWriter(outputDirectory.resolve("latency.csv"))) {
            writer.write("bucketSecond,count,p50Millis,p95Millis,p99Millis,maxMillis");
            writer.newLine();

            for (LatencyBucketSummary bucket : metrics.bucketSummaries()) {
                writer.write(bucket.bucketSecond() + "," + bucket.count() + "," +
                        formatMillis(bucket.p50Nanos()) + "," +
                        formatMillis(bucket.p95Nanos()) + "," +
                        formatMillis(bucket.p99Nanos()) + "," +
                        formatMillis(bucket.maxNanos()));
                writer.newLine();
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputDirectory.resolve("ack-counts.csv"))) {
            writer.write("resultCode,count");
            writer.newLine();
            for (Map.Entry<Integer, Long> entry : summary.ackCounts().entrySet()) {
                writer.write(entry.getKey() + "," + entry.getValue());
                writer.newLine();
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputDirectory.resolve("client-errors.csv"))) {
            writer.write("errorType,count");
            writer.newLine();
            for (Map.Entry<String, Long> entry : summary.clientErrors().entrySet()) {
                writer.write(entry.getKey() + "," + entry.getValue());
                writer.newLine();
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputDirectory.resolve("ramp-steps.csv"))) {
            writer.write("stepIndex,targetRate,sent,ackCount,busyCount,timeoutCount,errorCount,actualThroughputPerSecond,busyRatio,timeoutRatio,errorRatio,p50Millis,p95Millis,p99Millis,maxMillis,breachedMetrics");
            writer.newLine();
            for (RampStepSummary step : metrics.rampSteps()) {
                writer.write(step.stepIndex() + "," +
                        step.targetRate() + "," +
                        step.sent() + "," +
                        step.ackCount() + "," +
                        step.busyCount() + "," +
                        step.timeoutCount() + "," +
                        step.errorCount() + "," +
                        String.format(Locale.US, "%.3f", step.actualThroughputPerSecond()) + "," +
                        String.format(Locale.US, "%.4f", step.busyRatio()) + "," +
                        String.format(Locale.US, "%.4f", step.timeoutRatio()) + "," +
                        String.format(Locale.US, "%.4f", step.errorRatio()) + "," +
                        String.format(Locale.US, "%.3f", step.p50Millis()) + "," +
                        String.format(Locale.US, "%.3f", step.p95Millis()) + "," +
                        String.format(Locale.US, "%.3f", step.p99Millis()) + "," +
                        String.format(Locale.US, "%.3f", step.maxMillis()) + "," +
                        escapeCsv(String.join(" | ", step.breachedMetrics())));
                writer.newLine();
            }
        }
    }

    private static void printSummary(LoadTestSummary summary) {
        System.out.println("Robot load test summary");
        System.out.println("scenario=" + summary.scenario());
        System.out.println("host=" + summary.host() + ":" + summary.port());
        System.out.println("runnerId=" + summary.runnerId());
        System.out.println("connections=" + summary.connections());
        System.out.println("totalSent=" + summary.totalSent());
        System.out.println("totalAcks=" + summary.totalAcks());
        System.out.println("durationSeconds=" + String.format(Locale.US, "%.2f", summary.measurementDurationSeconds()));
        System.out.println("throughputPerSecond=" + String.format(Locale.US, "%.2f", summary.throughputPerSecond()));
        System.out.println("latencyP50Millis=" + String.format(Locale.US, "%.2f", summary.p50Millis()));
        System.out.println("latencyP95Millis=" + String.format(Locale.US, "%.2f", summary.p95Millis()));
        System.out.println("latencyP99Millis=" + String.format(Locale.US, "%.2f", summary.p99Millis()));
        System.out.println("ackCounts=" + summary.ackCounts());
        if (!summary.clientErrors().isEmpty()) {
            System.out.println("clientErrors=" + summary.clientErrors());
        }
        if (summary.failureThreshold().triggered()) {
            System.out.println("stopReason=" + summary.failureThreshold().reason());
            System.out.println("breachedStep=" + summary.failureThreshold().breachedStepIndex());
            System.out.println("breachedTargetRate=" + summary.failureThreshold().breachedTargetRate());
            System.out.println("breachedMetrics=" + summary.failureThreshold().breachedMetrics());
        }
        System.out.println("reportDir=" + summary.reportDirectory());
    }

    private static void waitUntil(long targetNanos) {
        while (true) {
            long remaining = targetNanos - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            LockSupport.parkNanos(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)));
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static long nanosUntilBarrier(long barrierEpochMillis) {
        if (barrierEpochMillis <= 0) {
            return 0L;
        }
        long remainingMillis = barrierEpochMillis - Instant.now().toEpochMilli();
        return Math.max(0L, TimeUnit.MILLISECONDS.toNanos(remainingMillis));
    }

    private static void sleepMillis(int pauseMillis) {
        try {
            Thread.sleep(pauseMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.US, "%.3f", nanos / 1_000_000.0);
    }

    private static String escapeCsv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    record LoadAck(int robotType, int resultCode, int requestOpCode, String message) {
    }

    record RobotMessage(byte robotType, byte opCode, byte[] payload) {
    }

    enum LoadScenario {
        BURST,
        SUSTAINED,
        SOAK,
        SATURATION;

        static LoadScenario fromProperty(String value) {
            return LoadScenario.valueOf(value.trim().toUpperCase(Locale.US));
        }
    }

    enum PayloadProfile {
        SMALL,
        MEDIUM,
        LARGE;

        static PayloadProfile fromProperty(String value) {
            return PayloadProfile.valueOf(value.trim().toUpperCase(Locale.US));
        }
    }

    record RobotTypeMix(double typeARatio) {
        static RobotTypeMix parse(String value) {
            if (value == null || value.isBlank()) {
                return new RobotTypeMix(0.5d);
            }

            String[] entries = value.split(",");
            double a = 50d;
            double b = 50d;
            for (String entry : entries) {
                String[] parts = entry.split(":");
                if (parts.length != 2) {
                    continue;
                }
                String key = parts[0].trim().toUpperCase(Locale.US);
                double parsed = Double.parseDouble(parts[1].trim());
                if ("A".equals(key)) {
                    a = parsed;
                } else if ("B".equals(key)) {
                    b = parsed;
                }
            }

            double total = a + b;
            if (total <= 0) {
                return new RobotTypeMix(0.5d);
            }
            return new RobotTypeMix(a / total);
        }
    }

    record StopCondition(
            double busyRatioThreshold,
            double timeoutRatioThreshold,
            double errorRatioThreshold,
            double p99MillisThreshold,
            int consecutiveBreaches
    ) {
        static StopCondition fromSystemProperties() {
            return new StopCondition(
                    Double.parseDouble(System.getProperty("robot.load.stop.busyRatio", "0.05")),
                    Double.parseDouble(System.getProperty("robot.load.stop.timeoutRatio", "0.01")),
                    Double.parseDouble(System.getProperty("robot.load.stop.errorRatio", "0.01")),
                    Double.parseDouble(System.getProperty("robot.load.stop.p99Millis", "100")),
                    Integer.getInteger("robot.load.stop.consecutiveBreaches", 2)
            );
        }

        Map<String, Object> asJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("busyRatioThreshold", busyRatioThreshold);
            json.put("timeoutRatioThreshold", timeoutRatioThreshold);
            json.put("errorRatioThreshold", errorRatioThreshold);
            json.put("p99MillisThreshold", p99MillisThreshold);
            json.put("consecutiveBreaches", consecutiveBreaches);
            return json;
        }
    }

    record LoadTestConfig(
            String host,
            int port,
            int connections,
            LoadScenario scenario,
            int messagesPerConnection,
            int durationSeconds,
            int targetRate,
            int warmupSeconds,
            int pauseMillis,
            double statusRatio,
            RobotTypeMix robotTypeMix,
            PayloadProfile payloadProfile,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            int initialRate,
            int maxTargetRate,
            int rateStep,
            int stepDurationSeconds,
            StopCondition stopCondition,
            String runnerId,
            long startBarrierEpochMillis,
            Path outputDirectory
    ) {
        static LoadTestConfig fromSystemProperties() {
            LoadScenario scenario = LoadScenario.fromProperty(System.getProperty("robot.load.scenario", "burst"));
            int durationSeconds = Integer.getInteger(
                    "robot.load.maxDurationSeconds",
                    Integer.getInteger("robot.load.durationSeconds", scenario == LoadScenario.BURST ? 0 : 60)
            );
            int targetRate = Integer.getInteger("robot.load.targetRate", scenario == LoadScenario.BURST ? 0 : 10_000);
            int messagesPerConnection = Integer.getInteger("robot.load.messagesPerConnection", scenario == LoadScenario.BURST ? 100 : 0);
            int rateStep = Integer.getInteger(
                    "robot.load.rateStep",
                    Integer.getInteger("robot.load.saturationStepRate", 5_000)
            );
            int stepDurationSeconds = Integer.getInteger(
                    "robot.load.stepDurationSeconds",
                    Integer.getInteger("robot.load.saturationStepDurationSeconds", 15)
            );
            int initialRate = Integer.getInteger(
                    "robot.load.initialRate",
                    scenario == LoadScenario.SATURATION ? Math.max(1, rateStep) : Math.max(1, targetRate)
            );
            int maxTargetRate = Integer.getInteger(
                    "robot.load.maxTargetRate",
                    scenario == LoadScenario.SATURATION ? Math.max(targetRate, initialRate) : Math.max(1, targetRate)
            );
            String runnerId = System.getProperty("robot.load.runnerId", defaultRunnerId());
            String baseReportDir = System.getProperty("robot.load.reportDir", "build/reports/robot-load");
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());

            return new LoadTestConfig(
                    System.getProperty("robot.load.host", "127.0.0.1"),
                    Integer.getInteger("robot.load.port", 29001),
                    Integer.getInteger("robot.load.connections", 20),
                    scenario,
                    messagesPerConnection,
                    durationSeconds,
                    targetRate,
                    Integer.getInteger("robot.load.warmupSeconds", 0),
                    Integer.getInteger("robot.load.pauseMillis", 0),
                    Double.parseDouble(System.getProperty("robot.load.statusRatio", "0.7")),
                    RobotTypeMix.parse(System.getProperty("robot.load.robotTypeMix", "A:50,B:50")),
                    PayloadProfile.fromProperty(System.getProperty("robot.load.payloadProfile", "small")),
                    Integer.getInteger("robot.load.connectTimeoutMillis", 3000),
                    Integer.getInteger("robot.load.readTimeoutMillis", 3000),
                    initialRate,
                    Math.max(initialRate, maxTargetRate),
                    Math.max(1, rateStep),
                    Math.max(1, stepDurationSeconds),
                    StopCondition.fromSystemProperties(),
                    runnerId,
                    Long.getLong("robot.load.startBarrierEpochMillis", 0L),
                    Path.of(baseReportDir, timestamp + "-" + runnerId)
            );
        }

        private static String defaultRunnerId() {
            String processId = ManagementFactory.getRuntimeMXBean().getName().replace('@', '-');
            try {
                return java.net.InetAddress.getLocalHost().getHostName() + "-" + processId;
            } catch (UnknownHostException e) {
                return "runner-" + processId;
            }
        }
    }

    static final class LoadTestMetrics {
        private final LoadTestConfig config;
        private final long measurementStartNanos;
        private final long measurementEndNanos;
        private final LongAdder totalSent = new LongAdder();
        private final LongAdder totalAcks = new LongAdder();
        private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentHashMap<Long, List<Long>> latencyBuckets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, LongAdder> ackCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> clientErrors = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, StepMetrics> stepMetrics = new ConcurrentHashMap<>();
        private final List<RampStepSummary> rampSteps = new ArrayList<>();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicLong stopRequestedNanos = new AtomicLong(Long.MAX_VALUE);

        private volatile FailureThreshold failureThreshold = FailureThreshold.notTriggered();
        private volatile RampStepSummary lastHealthyStep;
        private int lastEvaluatedStepIndex = -1;
        private int consecutiveBreaches;

        private LoadTestMetrics(LoadTestConfig config, long measurementStartNanos, long measurementEndNanos) {
            this.config = config;
            this.measurementStartNanos = measurementStartNanos;
            this.measurementEndNanos = measurementEndNanos;
        }

        boolean shouldSample(long sendStartedNanos) {
            return sendStartedNanos >= measurementStartNanos && sendStartedNanos < measurementEndNanos;
        }

        boolean shouldStop() {
            return stopRequested.get();
        }

        void recordSent(long sendStartedNanos, long targetRate) {
            totalSent.increment();
            if (config.scenario() != LoadScenario.SATURATION) {
                return;
            }

            stepMetricsFor(sendStartedNanos, targetRate).sent.increment();
            maybeEvaluateClosedSteps(sendStartedNanos, false);
        }

        void recordAck(int resultCode, long sendStartedNanos, long completedNanos, long targetRate) {
            totalAcks.increment();
            latenciesNanos.add(completedNanos - sendStartedNanos);
            ackCounts.computeIfAbsent(resultCode, ignored -> new LongAdder()).increment();

            long bucketSecond = TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, completedNanos - measurementStartNanos));
            latencyBuckets.computeIfAbsent(bucketSecond, ignored -> Collections.synchronizedList(new ArrayList<>()))
                    .add(completedNanos - sendStartedNanos);

            if (config.scenario() != LoadScenario.SATURATION) {
                return;
            }

            StepMetrics step = stepMetricsFor(sendStartedNanos, targetRate);
            step.ackCount.increment();
            step.latenciesNanos.add(completedNanos - sendStartedNanos);
            if (resultCode == Byte.toUnsignedInt(RobotAckResultCode.BUSY.getCode())) {
                step.busyCount.increment();
            } else if (resultCode != Byte.toUnsignedInt(RobotAckResultCode.SUCCESS.getCode())) {
                step.errorCount.increment();
            }
            maybeEvaluateClosedSteps(completedNanos, false);
        }

        void recordClientError(String errorType, long sendStartedNanos, long completedNanos, long targetRate) {
            clientErrors.computeIfAbsent(errorType, ignored -> new LongAdder()).increment();
            if (config.scenario() != LoadScenario.SATURATION) {
                return;
            }

            StepMetrics step = stepMetricsFor(sendStartedNanos, targetRate);
            if (Objects.equals(errorType, SocketTimeoutException.class.getSimpleName())) {
                step.timeoutCount.increment();
            } else {
                step.errorCount.increment();
            }
            maybeEvaluateClosedSteps(completedNanos, false);
        }

        synchronized void complete(long finishedAtNanos) {
            maybeEvaluateClosedSteps(finishedAtNanos, true);
        }

        synchronized List<RampStepSummary> rampSteps() {
            return new ArrayList<>(rampSteps);
        }

        List<LatencyBucketSummary> bucketSummaries() {
            return latencyBuckets.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> {
                        List<Long> samples = new ArrayList<>(entry.getValue());
                        samples.sort(Comparator.naturalOrder());
                        return new LatencyBucketSummary(
                                entry.getKey(),
                                samples.size(),
                                percentile(samples, 0.50),
                                percentile(samples, 0.95),
                                percentile(samples, 0.99),
                                samples.isEmpty() ? 0L : samples.get(samples.size() - 1)
                        );
                    })
                    .toList();
        }

        LoadTestSummary toSummary(LoadTestConfig config, Instant startedAt, Instant finishedAt, long finishedAtNanos) {
            List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
            sortedLatencies.sort(Comparator.naturalOrder());

            long effectiveEndNanos = Math.min(
                    finishedAtNanos,
                    Math.min(measurementEndNanos, stopRequestedNanos.get())
            );
            if (effectiveEndNanos == Long.MAX_VALUE) {
                effectiveEndNanos = finishedAtNanos;
            }

            Duration measurementDuration = Duration.ofNanos(Math.max(0L, effectiveEndNanos - measurementStartNanos));
            long sent = totalSent.sum();
            long ack = totalAcks.sum();
            double durationSeconds = Math.max(0.001d, measurementDuration.toMillis() / 1000.0d);
            double throughput = ack / durationSeconds;

            return new LoadTestSummary(
                    config.scenario().name(),
                    config.host(),
                    config.port(),
                    config.runnerId(),
                    config.connections(),
                    sent,
                    ack,
                    durationSeconds,
                    throughput,
                    nanosToMillis(percentile(sortedLatencies, 0.50)),
                    nanosToMillis(percentile(sortedLatencies, 0.95)),
                    nanosToMillis(percentile(sortedLatencies, 0.99)),
                    toAckCountMap(ackCounts),
                    toClientErrorMap(clientErrors),
                    config.outputDirectory().toString(),
                    startedAt.toString(),
                    finishedAt.toString(),
                    failureThreshold,
                    config
            );
        }

        private StepMetrics stepMetricsFor(long eventNanos, long targetRate) {
            int stepIndex = stepIndexForEvent(eventNanos);
            return stepMetrics.computeIfAbsent(stepIndex, ignored -> new StepMetrics(stepIndex, targetRate));
        }

        private synchronized void maybeEvaluateClosedSteps(long observedNanos, boolean force) {
            if (config.scenario() != LoadScenario.SATURATION) {
                return;
            }

            int latestObservedStep = stepIndexForEvent(observedNanos);
            int eligibleStep = force ? latestObservedStep : latestObservedStep - 1;

            while (lastEvaluatedStepIndex < eligibleStep) {
                int nextStep = lastEvaluatedStepIndex + 1;
                StepMetrics step = stepMetrics.get(nextStep);
                lastEvaluatedStepIndex = nextStep;
                if (step == null) {
                    continue;
                }

                RampStepSummary summary = summarizeStep(step, observedNanos);
                rampSteps.add(summary);

                if (summary.breachedMetrics().isEmpty()) {
                    consecutiveBreaches = 0;
                    lastHealthyStep = summary;
                    continue;
                }

                consecutiveBreaches++;
                if (consecutiveBreaches >= config.stopCondition().consecutiveBreaches() && stopRequested.compareAndSet(false, true)) {
                    stopRequestedNanos.set(observedNanos);
                    failureThreshold = FailureThreshold.triggered(
                            "threshold breached",
                            summary.stepIndex(),
                            summary.targetRate(),
                            consecutiveBreaches,
                            summary.breachedMetrics(),
                            lastHealthyStep,
                            summary
                    );
                }
            }
        }

        private RampStepSummary summarizeStep(StepMetrics step, long observedNanos) {
            List<Long> sortedLatencies = new ArrayList<>(step.latenciesNanos);
            sortedLatencies.sort(Comparator.naturalOrder());

            long stepStartNanos = measurementStartNanos + (long) step.stepIndex * TimeUnit.SECONDS.toNanos(config.stepDurationSeconds());
            long stepEndNanos = Math.min(
                    stepStartNanos + TimeUnit.SECONDS.toNanos(config.stepDurationSeconds()),
                    Math.max(observedNanos, stepStartNanos + 1)
            );

            double stepDurationSeconds = Math.max(0.001d, (stepEndNanos - stepStartNanos) / 1_000_000_000.0d);
            long sent = step.sent.sum();
            long ackCount = step.ackCount.sum();
            long busyCount = step.busyCount.sum();
            long timeoutCount = step.timeoutCount.sum();
            long errorCount = step.errorCount.sum();
            double actualThroughput = ackCount / stepDurationSeconds;
            double busyRatio = sent == 0 ? 0d : busyCount / (double) sent;
            double timeoutRatio = sent == 0 ? 0d : timeoutCount / (double) sent;
            double errorRatio = sent == 0 ? 0d : errorCount / (double) sent;
            double p50Millis = nanosToMillis(percentile(sortedLatencies, 0.50));
            double p95Millis = nanosToMillis(percentile(sortedLatencies, 0.95));
            double p99Millis = nanosToMillis(percentile(sortedLatencies, 0.99));
            double maxMillis = nanosToMillis(sortedLatencies.isEmpty() ? 0L : sortedLatencies.get(sortedLatencies.size() - 1));
            List<String> breachedMetrics = evaluateBreaches(busyRatio, timeoutRatio, errorRatio, p99Millis);

            return new RampStepSummary(
                    step.stepIndex,
                    step.targetRate,
                    sent,
                    ackCount,
                    busyCount,
                    timeoutCount,
                    errorCount,
                    actualThroughput,
                    busyRatio,
                    timeoutRatio,
                    errorRatio,
                    p50Millis,
                    p95Millis,
                    p99Millis,
                    maxMillis,
                    breachedMetrics
            );
        }

        private List<String> evaluateBreaches(double busyRatio, double timeoutRatio, double errorRatio, double p99Millis) {
            List<String> breached = new ArrayList<>();
            if (busyRatio >= config.stopCondition().busyRatioThreshold()) {
                breached.add("busyRatio=" + formatRatio(busyRatio) + " threshold=" + formatRatio(config.stopCondition().busyRatioThreshold()));
            }
            if (timeoutRatio >= config.stopCondition().timeoutRatioThreshold()) {
                breached.add("timeoutRatio=" + formatRatio(timeoutRatio) + " threshold=" + formatRatio(config.stopCondition().timeoutRatioThreshold()));
            }
            if (errorRatio >= config.stopCondition().errorRatioThreshold()) {
                breached.add("errorRatio=" + formatRatio(errorRatio) + " threshold=" + formatRatio(config.stopCondition().errorRatioThreshold()));
            }
            if (p99Millis >= config.stopCondition().p99MillisThreshold()) {
                breached.add("p99Millis=" + String.format(Locale.US, "%.3f", p99Millis) +
                        " threshold=" + String.format(Locale.US, "%.3f", config.stopCondition().p99MillisThreshold()));
            }
            return breached;
        }

        private String formatRatio(double value) {
            return String.format(Locale.US, "%.4f", value);
        }

        private int stepIndexForEvent(long eventNanos) {
            long elapsedNanos = Math.max(0L, eventNanos - measurementStartNanos);
            return (int) (TimeUnit.NANOSECONDS.toSeconds(elapsedNanos) / Math.max(1, config.stepDurationSeconds()));
        }

        private Map<Integer, Long> toAckCountMap(ConcurrentHashMap<Integer, LongAdder> source) {
            return source.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().sum(),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
        }

        private Map<String, Long> toClientErrorMap(ConcurrentHashMap<String, LongAdder> source) {
            return source.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().sum(),
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));
        }

        long measurementEndNanos() {
            return measurementEndNanos;
        }

        long measurementStartNanos() {
            return measurementStartNanos;
        }
    }

    private static final class StepMetrics {
        private final int stepIndex;
        private final long targetRate;
        private final LongAdder sent = new LongAdder();
        private final LongAdder ackCount = new LongAdder();
        private final LongAdder busyCount = new LongAdder();
        private final LongAdder timeoutCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());

        private StepMetrics(int stepIndex, long targetRate) {
            this.stepIndex = stepIndex;
            this.targetRate = targetRate;
        }
    }

    record LatencyBucketSummary(
            long bucketSecond,
            int count,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos
    ) {
    }

    record RampStepSummary(
            int stepIndex,
            long targetRate,
            long sent,
            long ackCount,
            long busyCount,
            long timeoutCount,
            long errorCount,
            double actualThroughputPerSecond,
            double busyRatio,
            double timeoutRatio,
            double errorRatio,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis,
            List<String> breachedMetrics
    ) {
    }

    record FailureThreshold(
            boolean triggered,
            String reason,
            Integer breachedStepIndex,
            Long breachedTargetRate,
            int consecutiveBreaches,
            String triggeredAt,
            List<String> breachedMetrics,
            RampStepSummary lastHealthyStep,
            RampStepSummary triggeredStep
    ) {
        static FailureThreshold notTriggered() {
            return new FailureThreshold(false, "not_triggered", null, null, 0, null, List.of(), null, null);
        }

        static FailureThreshold triggered(
                String reason,
                int breachedStepIndex,
                long breachedTargetRate,
                int consecutiveBreaches,
                List<String> breachedMetrics,
                RampStepSummary lastHealthyStep,
                RampStepSummary triggeredStep
        ) {
            return new FailureThreshold(
                    true,
                    reason,
                    breachedStepIndex,
                    breachedTargetRate,
                    consecutiveBreaches,
                    Instant.now().toString(),
                    List.copyOf(breachedMetrics),
                    lastHealthyStep,
                    triggeredStep
            );
        }

        Map<String, Object> asJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("triggered", triggered);
            json.put("reason", reason);
            json.put("breachedStepIndex", breachedStepIndex);
            json.put("breachedTargetRate", breachedTargetRate);
            json.put("consecutiveBreaches", consecutiveBreaches);
            json.put("triggeredAt", triggeredAt);
            json.put("breachedMetrics", breachedMetrics);
            json.put("lastHealthyStep", lastHealthyStep);
            json.put("triggeredStep", triggeredStep);
            return json;
        }
    }

    record LoadTestSummary(
            String scenario,
            String host,
            int port,
            String runnerId,
            int connections,
            long totalSent,
            long totalAcks,
            double measurementDurationSeconds,
            double throughputPerSecond,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            Map<Integer, Long> ackCounts,
            Map<String, Long> clientErrors,
            String reportDirectory,
            String startedAt,
            String finishedAt,
            FailureThreshold failureThreshold,
            LoadTestConfig config
    ) {
        Map<String, Object> asJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("scenario", scenario);
            json.put("host", host);
            json.put("port", port);
            json.put("runnerId", runnerId);
            json.put("connections", connections);
            json.put("totalSent", totalSent);
            json.put("totalAcks", totalAcks);
            json.put("measurementDurationSeconds", measurementDurationSeconds);
            json.put("throughputPerSecond", throughputPerSecond);

            Map<String, Object> latency = new LinkedHashMap<>();
            latency.put("p50Millis", p50Millis);
            latency.put("p95Millis", p95Millis);
            latency.put("p99Millis", p99Millis);
            json.put("latency", latency);

            json.put("ackCounts", ackCounts);
            json.put("clientErrors", clientErrors);
            json.put("reportDirectory", reportDirectory);
            json.put("startedAt", startedAt);
            json.put("finishedAt", finishedAt);
            json.put("failureThreshold", failureThreshold.asJson());

            Map<String, Object> configJson = new LinkedHashMap<>();
            configJson.put("scenario", config.scenario().name());
            configJson.put("messagesPerConnection", config.messagesPerConnection());
            configJson.put("durationSeconds", config.durationSeconds());
            configJson.put("targetRate", config.targetRate());
            configJson.put("warmupSeconds", config.warmupSeconds());
            configJson.put("payloadProfile", config.payloadProfile().name());
            configJson.put("statusRatio", config.statusRatio());
            configJson.put(
                    "robotTypeMix",
                    "A:" + String.format(Locale.US, "%.2f", config.robotTypeMix().typeARatio() * 100d) +
                            ",B:" + String.format(Locale.US, "%.2f", (1d - config.robotTypeMix().typeARatio()) * 100d)
            );
            configJson.put("initialRate", config.initialRate());
            configJson.put("maxTargetRate", config.maxTargetRate());
            configJson.put("rateStep", config.rateStep());
            configJson.put("stepDurationSeconds", config.stepDurationSeconds());
            configJson.put("stopCondition", config.stopCondition().asJson());
            json.put("config", configJson);

            return json;
        }
    }

    private static long percentile(List<Long> latencies, double percentile) {
        if (latencies.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * latencies.size()) - 1;
        index = Math.max(0, Math.min(index, latencies.size() - 1));
        return latencies.get(index);
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }
}
