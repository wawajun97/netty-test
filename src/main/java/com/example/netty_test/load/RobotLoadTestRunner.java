package com.example.netty_test.load;

import com.example.netty_test.protocol.RobotConstants;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public final class RobotLoadTestRunner {
    private RobotLoadTestRunner() {
    }

    public static void main(String[] args) throws InterruptedException {
        LoadTestConfig config = LoadTestConfig.fromSystemProperties();
        LoadTestMetrics metrics = new LoadTestMetrics();
        ExecutorService executorService = Executors.newFixedThreadPool(config.connections());
        CountDownLatch latch = new CountDownLatch(config.connections());
        Instant startedAt = Instant.now();

        for (int connectionIndex = 0; connectionIndex < config.connections(); connectionIndex++) {
            final int clientId = connectionIndex;
            executorService.submit(() -> {
                try {
                    runClient(config, metrics, clientId);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.MINUTES);
        metrics.printSummary(Duration.between(startedAt, Instant.now()), config);
    }

    private static void runClient(LoadTestConfig config, LoadTestMetrics metrics, int clientId) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMillis());
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(config.readTimeoutMillis());

            try (DataInputStream inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {

                for (int messageIndex = 0; messageIndex < config.messagesPerConnection(); messageIndex++) {
                    // 상태/위치 메시지를 섞어서 실제 운영에서의 mixed workload를 흉내 낸다.
                    byte robotType = messageIndex % 2 == 0 ? RobotConstants.ROBOT_TYPE_A : RobotConstants.ROBOT_TYPE_B;
                    byte opCode = chooseOpCode(config.statusRatio());
                    byte[] payload = opCode == RobotConstants.STATUS_OP_CODE
                            ? buildStatusPayload(clientId, messageIndex)
                            : buildPositionPayload(clientId, messageIndex);

                    long startedAt = System.nanoTime();
                    writeFrame(outputStream, robotType, opCode, payload);
                    LoadAck ack = readAck(inputStream);
                    metrics.recordAck(ack.resultCode(), System.nanoTime() - startedAt);

                    if (config.pauseMillis() > 0) {
                        Thread.sleep(config.pauseMillis());
                    }
                }
            }
        } catch (Exception e) {
            metrics.recordClientError(e.getClass().getSimpleName());
        }
    }

    private static byte chooseOpCode(double statusRatio) {
        return ThreadLocalRandom.current().nextDouble() < statusRatio
                ? RobotConstants.STATUS_OP_CODE
                : RobotConstants.POSITION_OP_CODE;
    }

    private static byte[] buildStatusPayload(int clientId, int messageIndex) throws Exception {
        String robotId = "robot-" + clientId;
        byte[] robotIdBytes = robotId.getBytes(StandardCharsets.UTF_8);

        java.io.ByteArrayOutputStream byteArrayOutputStream = new java.io.ByteArrayOutputStream();
        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
        dataOutputStream.writeShort(robotIdBytes.length);
        dataOutputStream.write(robotIdBytes);
        dataOutputStream.writeLong(System.currentTimeMillis());
        dataOutputStream.writeByte(messageIndex % 10);
        dataOutputStream.writeByte(20 + (messageIndex % 80));
        dataOutputStream.flush();
        return byteArrayOutputStream.toByteArray();
    }

    private static byte[] buildPositionPayload(int clientId, int messageIndex) throws Exception {
        String robotId = "robot-" + clientId;
        byte[] robotIdBytes = robotId.getBytes(StandardCharsets.UTF_8);

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
    }

    private static void writeFrame(DataOutputStream outputStream, byte robotType, byte opCode, byte[] payload) throws Exception {
        outputStream.writeByte(RobotConstants.STX);
        outputStream.writeInt(payload.length);
        outputStream.writeByte(robotType);
        outputStream.writeByte(opCode);
        outputStream.write(payload);
        outputStream.writeByte(RobotConstants.ETX);
        outputStream.flush();
    }

    private static LoadAck readAck(DataInputStream inputStream) throws Exception {
        // 서버 응답도 같은 STX/ETX 기반 프레임인지 검증하면서 latency를 기록한다.
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

    private record LoadAck(int robotType, int resultCode, int requestOpCode, String message) {
    }

    private record LoadTestConfig(
            String host,
            int port,
            int connections,
            int messagesPerConnection,
            double statusRatio,
            int pauseMillis,
            int connectTimeoutMillis,
            int readTimeoutMillis
    ) {
        private static LoadTestConfig fromSystemProperties() {
            return new LoadTestConfig(
                    System.getProperty("robot.load.host", "127.0.0.1"),
                    Integer.getInteger("robot.load.port", 29001),
                    Integer.getInteger("robot.load.connections", 20),
                    Integer.getInteger("robot.load.messagesPerConnection", 100),
                    Double.parseDouble(System.getProperty("robot.load.statusRatio", "0.7")),
                    Integer.getInteger("robot.load.pauseMillis", 0),
                    Integer.getInteger("robot.load.connectTimeoutMillis", 3000),
                    Integer.getInteger("robot.load.readTimeoutMillis", 3000)
            );
        }
    }

    private static final class LoadTestMetrics {
        private final List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<>());
        private final ConcurrentHashMap<Integer, LongAdder> ackCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> clientErrors = new ConcurrentHashMap<>();

        private void recordAck(int resultCode, long latencyNanos) {
            latenciesNanos.add(latencyNanos);
            ackCounts.computeIfAbsent(resultCode, ignored -> new LongAdder()).increment();
        }

        private void recordClientError(String errorType) {
            clientErrors.computeIfAbsent(errorType, ignored -> new LongAdder()).increment();
        }

        private void printSummary(Duration duration, LoadTestConfig config) {
            List<Long> sortedLatencies = new ArrayList<>(latenciesNanos);
            Collections.sort(sortedLatencies);

            long totalAcks = sortedLatencies.size();
            // burst 테스트 기준으로 처리량과 p50/p95/p99를 바로 읽을 수 있게 출력한다.
            double throughput = duration.toMillis() == 0
                    ? totalAcks
                    : totalAcks / (duration.toMillis() / 1000.0);

            System.out.println("Robot load test summary");
            System.out.println("host=" + config.host() + ":" + config.port());
            System.out.println("connections=" + config.connections());
            System.out.println("messagesPerConnection=" + config.messagesPerConnection());
            System.out.println("totalAcks=" + totalAcks);
            System.out.println("durationSeconds=" + String.format(Locale.US, "%.2f", duration.toMillis() / 1000.0));
            System.out.println("throughputPerSecond=" + String.format(Locale.US, "%.2f", throughput));

            if (!sortedLatencies.isEmpty()) {
                System.out.println("latencyP50Millis=" + nanosToMillis(percentile(sortedLatencies, 0.50)));
                System.out.println("latencyP95Millis=" + nanosToMillis(percentile(sortedLatencies, 0.95)));
                System.out.println("latencyP99Millis=" + nanosToMillis(percentile(sortedLatencies, 0.99)));
            }

            System.out.println("ackCounts=" + renderAckCounts());
            if (!clientErrors.isEmpty()) {
                System.out.println("clientErrors=" + renderClientErrors());
            }
        }

        private String renderAckCounts() {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (var entry : ackCounts.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(entry.getKey()).append('=').append(entry.getValue().sum());
                first = false;
            }
            builder.append('}');
            return builder.toString();
        }

        private String renderClientErrors() {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (var entry : clientErrors.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                builder.append(entry.getKey()).append('=').append(entry.getValue().sum());
                first = false;
            }
            builder.append('}');
            return builder.toString();
        }

        private long percentile(List<Long> latencies, double percentile) {
            int index = (int) Math.ceil(percentile * latencies.size()) - 1;
            index = Math.max(0, Math.min(index, latencies.size() - 1));
            return latencies.get(index);
        }

        private String nanosToMillis(long nanos) {
            return String.format(Locale.US, "%.2f", nanos / 1_000_000.0);
        }
    }
}
