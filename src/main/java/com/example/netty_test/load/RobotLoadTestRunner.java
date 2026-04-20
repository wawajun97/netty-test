package com.example.netty_test.load;

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
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

        LoadTestMetrics metrics = new LoadTestMetrics(
                config.runnerId(),
                measurementStartNanos,
                measurementEndNanos
        );

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

        Instant finishedAt = Instant.now();
        LoadTestSummary summary = metrics.toSummary(config, startedAt, finishedAt);
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
                    long beforeSendNanos = System.nanoTime();

                    if (config.scenario() != LoadScenario.BURST) {
                        waitUntil(plannedSendNanos);
                        beforeSendNanos = System.nanoTime();
                    }

                    boolean sampled = metrics.shouldSample(beforeSendNanos);
                    RobotMessage message = buildMessage(config, clientId, messageIndex);

                    if (sampled) {
                        metrics.recordSent();
                    }

                    try {
                        writeFrame(outputStream, message.robotType(), message.opCode(), message.payload());
                        LoadAck ack = readAck(inputStream);
                        if (sampled) {
                            metrics.recordAck(ack.resultCode(), System.nanoTime() - beforeSendNanos);
                        }
                    } catch (Exception e) {
                        if (sampled) {
                            metrics.recordClientError(e.getClass().getSimpleName());
                        }
                        break;
                    }

                    messageIndex++;
                    nextSendNanos = calculateNextSendNanos(config, messageIndex, beforeSendNanos, plannedSendNanos, metrics);

                    if (config.pauseMillis() > 0) {
                        sleepMillis(config.pauseMillis());
                    }
                }
            }
        } catch (Exception e) {
            if (metrics.isMeasurementWindowOpen()) {
                metrics.recordClientError(e.getClass().getSimpleName());
            }
        }
    }

    private static boolean shouldContinue(LoadTestConfig config, LoadTestMetrics metrics, int messageIndex) {
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
            int messageIndex,
            long actualSendNanos,
            long plannedSendNanos,
            LoadTestMetrics metrics
    ) {
        if (config.scenario() == LoadScenario.BURST) {
            return actualSendNanos;
        }

        long intervalNanos = switch (config.scenario()) {
            case SUSTAINED, SOAK -> nanosPerMessage(config.targetRate(), config.connections());
            case SATURATION -> nanosPerMessage(
                    currentSaturationRate(config, metrics.measurementStartNanos(), actualSendNanos),
                    config.connections()
            );
            default -> 0L;
        };

        return Math.max(actualSendNanos, plannedSendNanos) + intervalNanos;
    }

    private static long currentSaturationRate(LoadTestConfig config, long measurementStartNanos, long nowNanos) {
        if (config.targetRate() <= 0) {
            return 1L;
        }

        long elapsedNanos = Math.max(0L, nowNanos - measurementStartNanos);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(elapsedNanos);
        long currentStep = elapsedSeconds / Math.max(1, config.saturationStepDurationSeconds());
        long rate = (currentStep + 1) * Math.max(1, config.saturationStepRate());
        return Math.min(rate, config.targetRate());
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
            int saturationStepRate,
            int saturationStepDurationSeconds,
            String runnerId,
            long startBarrierEpochMillis,
            Path outputDirectory
    ) {
        static LoadTestConfig fromSystemProperties() {
            LoadScenario scenario = LoadScenario.fromProperty(System.getProperty("robot.load.scenario", "burst"));
            int durationSeconds = Integer.getInteger("robot.load.durationSeconds", scenario == LoadScenario.BURST ? 0 : 60);
            int targetRate = Integer.getInteger("robot.load.targetRate", scenario == LoadScenario.BURST ? 0 : 10_000);
            int messagesPerConnection = Integer.getInteger("robot.load.messagesPerConnection", scenario == LoadScenario.BURST ? 100 : 0);
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
                    Integer.getInteger("robot.load.saturationStepRate", 5_000),
                    Integer.getInteger("robot.load.saturationStepDurationSeconds", 15),
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
        private final String runnerId;
        private final long measurementStartNanos;
        private final long measurementEndNanos;
        private final LongAdder totalSent = new LongAdder();
        private final LongAdder totalAcks = new LongAdder();
        private final List<Long> latenciesNanos = java.util.Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentHashMap<Long, List<Long>> latencyBuckets = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Integer, LongAdder> ackCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> clientErrors = new ConcurrentHashMap<>();

        private LoadTestMetrics(String runnerId, long measurementStartNanos, long measurementEndNanos) {
            this.runnerId = runnerId;
            this.measurementStartNanos = measurementStartNanos;
            this.measurementEndNanos = measurementEndNanos;
        }

        boolean shouldSample(long sendStartedNanos) {
            return sendStartedNanos >= measurementStartNanos && sendStartedNanos < measurementEndNanos;
        }

        boolean isMeasurementWindowOpen() {
            long now = System.nanoTime();
            return now >= measurementStartNanos && now < measurementEndNanos;
        }

        void recordSent() {
            totalSent.increment();
        }

        void recordAck(int resultCode, long latencyNanos) {
            totalAcks.increment();
            latenciesNanos.add(latencyNanos);
            ackCounts.computeIfAbsent(resultCode, ignored -> new LongAdder()).increment();

            long bucketSecond = TimeUnit.NANOSECONDS.toSeconds(Math.max(0L, System.nanoTime() - measurementStartNanos));
            latencyBuckets.computeIfAbsent(bucketSecond, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                    .add(latencyNanos);
        }

        void recordClientError(String errorType) {
            clientErrors.computeIfAbsent(errorType, ignored -> new LongAdder()).increment();
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

        LoadTestSummary toSummary(LoadTestConfig config, Instant startedAt, Instant finishedAt) {
            List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
            sortedLatencies.sort(Comparator.naturalOrder());

            Duration measurementDuration = measurementEndNanos == Long.MAX_VALUE
                    ? Duration.between(startedAt, finishedAt)
                    : Duration.ofNanos(Math.max(0L, measurementEndNanos - measurementStartNanos));

            long sent = totalSent.sum();
            long ack = totalAcks.sum();
            double durationSeconds = Math.max(0.001d, measurementDuration.toMillis() / 1000.0d);
            double throughput = ack / durationSeconds;

            return new LoadTestSummary(
                    config.scenario().name(),
                    config.host(),
                    config.port(),
                    runnerId,
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
                    config
            );
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

    record LatencyBucketSummary(
            long bucketSecond,
            int count,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long maxNanos
    ) {
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
            json.put("latency", Map.of(
                    "p50Millis", p50Millis,
                    "p95Millis", p95Millis,
                    "p99Millis", p99Millis
            ));
            json.put("ackCounts", ackCounts);
            json.put("clientErrors", clientErrors);
            json.put("reportDirectory", reportDirectory);
            json.put("startedAt", startedAt);
            json.put("finishedAt", finishedAt);
            json.put("config", Map.of(
                    "scenario", config.scenario().name(),
                    "messagesPerConnection", config.messagesPerConnection(),
                    "durationSeconds", config.durationSeconds(),
                    "targetRate", config.targetRate(),
                    "warmupSeconds", config.warmupSeconds(),
                    "payloadProfile", config.payloadProfile().name(),
                    "statusRatio", config.statusRatio(),
                    "robotTypeMix", "A:" + String.format(Locale.US, "%.2f", config.robotTypeMix().typeARatio() * 100d) +
                            ",B:" + String.format(Locale.US, "%.2f", (1d - config.robotTypeMix().typeARatio()) * 100d),
                    "saturationStepRate", config.saturationStepRate(),
                    "saturationStepDurationSeconds", config.saturationStepDurationSeconds()
            ));
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
