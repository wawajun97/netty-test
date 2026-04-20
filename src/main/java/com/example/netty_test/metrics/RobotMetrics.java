package com.example.netty_test.metrics;

import com.example.netty_test.protocol.RobotAckResultCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class RobotMetrics {
    private final MeterRegistry meterRegistry;

    public RobotMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void incrementFrameReceived(byte robotType, byte opCode) {
        meterRegistry.counter(
                "robot.frames.received",
                "robotType", byteTag(robotType),
                "opCode", byteTag(opCode)
        ).increment();
    }

    public void incrementDecodeFailure(String reason) {
        meterRegistry.counter("robot.frames.decode.failure", "reason", reason).increment();
    }

    public void incrementRouteMiss(byte robotType, byte opCode) {
        meterRegistry.counter(
                "robot.frames.route.miss",
                "robotType", byteTag(robotType),
                "opCode", byteTag(opCode)
        ).increment();
    }

    public void incrementAck(RobotAckResultCode resultCode, byte robotType, byte opCode) {
        meterRegistry.counter(
                "robot.acks.sent",
                "resultCode", resultCode.name(),
                "robotType", byteTag(robotType),
                "opCode", byteTag(opCode)
        ).increment();
    }

    public void recordBatchInsert(String type, int batchSize, long durationNanos, boolean success) {
        Timer.builder("robot.persistence.batch")
                .tag("type", type)
                .tag("success", Boolean.toString(success))
                .register(meterRegistry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        meterRegistry.summary("robot.persistence.batch.size", "type", type).record(batchSize);
    }

    public void incrementPersistenceFailure(String type) {
        meterRegistry.counter("robot.persistence.failure", "type", type).increment();
    }

    private String byteTag(byte value) {
        return Integer.toString(Byte.toUnsignedInt(value));
    }
}
