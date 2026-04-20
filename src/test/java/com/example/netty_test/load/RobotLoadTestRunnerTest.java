package com.example.netty_test.load;

import com.example.netty_test.protocol.RobotConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class RobotLoadTestRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void writesScenarioReports() throws Exception {
        try (FakeRobotServer fakeRobotServer = new FakeRobotServer()) {
            fakeRobotServer.start();

            RobotLoadTestRunner.LoadTestConfig config = new RobotLoadTestRunner.LoadTestConfig(
                    "127.0.0.1",
                    fakeRobotServer.port(),
                    2,
                    RobotLoadTestRunner.LoadScenario.BURST,
                    3,
                    0,
                    0,
                    0,
                    0,
                    0.7d,
                    new RobotLoadTestRunner.RobotTypeMix(0.5d),
                    RobotLoadTestRunner.PayloadProfile.SMALL,
                    2_000,
                    2_000,
                    5_000,
                    15,
                    "test-runner",
                    0L,
                    tempDir.resolve("report")
            );

            RobotLoadTestRunner.LoadTestSummary summary = RobotLoadTestRunner.execute(config);

            assertThat(summary.totalSent()).isEqualTo(6);
            assertThat(summary.totalAcks()).isEqualTo(6);
            assertThat(summary.clientErrors()).isEmpty();
            assertThat(Files.exists(tempDir.resolve("report").resolve("summary.json"))).isTrue();
            assertThat(Files.exists(tempDir.resolve("report").resolve("latency.csv"))).isTrue();
            assertThat(Files.exists(tempDir.resolve("report").resolve("ack-counts.csv"))).isTrue();
            assertThat(Files.exists(tempDir.resolve("report").resolve("client-errors.csv"))).isTrue();
            assertThat(Files.readString(tempDir.resolve("report").resolve("summary.json"))).contains("\"totalAcks\" : 6");
        }
    }

    private static final class FakeRobotServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService executorService = Executors.newCachedThreadPool();
        private final AtomicBoolean running = new AtomicBoolean(true);

        private FakeRobotServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void start() {
            executorService.submit(() -> {
                while (running.get()) {
                    try {
                        Socket socket = serverSocket.accept();
                        executorService.submit(() -> handle(socket));
                    } catch (IOException ignored) {
                        if (!running.get()) {
                            return;
                        }
                    }
                }
            });
        }

        private void handle(Socket socket) {
            try (Socket ignored = socket;
                 DataInputStream inputStream = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                 DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
                while (running.get()) {
                    int stx;
                    try {
                        stx = inputStream.readUnsignedByte();
                    } catch (EOFException e) {
                        return;
                    }
                    if (stx != Byte.toUnsignedInt(RobotConstants.STX)) {
                        throw new EOFException("Unexpected STX");
                    }

                    int dataSize = inputStream.readInt();
                    int robotType = inputStream.readUnsignedByte();
                    int requestOpCode = inputStream.readUnsignedByte();
                    byte[] payload = inputStream.readNBytes(dataSize);
                    if (payload.length != dataSize) {
                        throw new EOFException("Payload truncated");
                    }
                    int etx = inputStream.readUnsignedByte();
                    if (etx != Byte.toUnsignedInt(RobotConstants.ETX)) {
                        throw new EOFException("Unexpected ETX");
                    }

                    byte[] message = "QUEUED".getBytes(StandardCharsets.UTF_8);
                    outputStream.writeByte(RobotConstants.STX);
                    outputStream.writeInt(1 + 1 + 2 + message.length);
                    outputStream.writeByte(robotType);
                    outputStream.writeByte(RobotConstants.ACK_OP_CODE);
                    outputStream.writeByte(0x00);
                    outputStream.writeByte(requestOpCode);
                    outputStream.writeShort(message.length);
                    outputStream.write(message);
                    outputStream.writeByte(RobotConstants.ETX);
                    outputStream.flush();
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            serverSocket.close();
            executorService.shutdownNow();
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
