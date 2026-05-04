package com.example.netty_test.application;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/*
* 로봇에서 들어온 데이터를 파싱
* 파싱 후 AbstractPositon(Status)OperationHandler에서 DB작업을 위한 큐에 저장
*/
public final class RobotPayloadReader {
    private RobotPayloadReader() {
    }

    public static RobotStatusPayload readStatus(ByteBuf payload) {
        // 공통 prefix(robotId, timestamp) 뒤에 상태 전용 필드를 해석한다.
        String robotId = readRobotId(payload);
        Instant occurredAt = readOccurredAt(payload);
        int statusCode = readUnsignedByte(payload, "statusCode");
        int batteryPercent = readUnsignedByte(payload, "batteryPercent");

        if (batteryPercent < 0 || batteryPercent > 100) {
            throw new IllegalArgumentException("batteryPercent must be between 0 and 100");
        }
        ensureFullyConsumed(payload);
        return new RobotStatusPayload(robotId, occurredAt, statusCode, batteryPercent);
    }

    public static RobotPositionPayload readPosition(ByteBuf payload) {
        // 위치 메시지는 좌표/방향값까지 읽고 남는 바이트가 있으면 포맷 오류로 본다.
        String robotId = readRobotId(payload);
        Instant occurredAt = readOccurredAt(payload);
        requireReadable(payload, 8, "x");
        double x = payload.readDouble();
        requireReadable(payload, 8, "y");
        double y = payload.readDouble();
        requireReadable(payload, 4, "headingDeg");
        float headingDeg = payload.readFloat();
        ensureFullyConsumed(payload);
        return new RobotPositionPayload(robotId, occurredAt, x, y, headingDeg);
    }

    private static String readRobotId(ByteBuf payload) {
        requireReadable(payload, 2, "robotIdLength");
        int robotIdLength = payload.readUnsignedShort();
        if (robotIdLength <= 0) {
            throw new IllegalArgumentException("robotIdLength must be positive");
        }
        requireReadable(payload, robotIdLength, "robotId");
        byte[] robotIdBytes = new byte[robotIdLength];
        payload.readBytes(robotIdBytes);
        String robotId = new String(robotIdBytes, StandardCharsets.UTF_8).trim();
        if (robotId.isEmpty()) {
            throw new IllegalArgumentException("robotId must not be blank");
        }
        return robotId;
    }

    private static Instant readOccurredAt(ByteBuf payload) {
        requireReadable(payload, 8, "timestampEpochMillis");
        long epochMillis = payload.readLong();
        if (epochMillis <= 0) {
            throw new IllegalArgumentException("timestampEpochMillis must be positive");
        }
        return Instant.ofEpochMilli(epochMillis);
    }

    private static int readUnsignedByte(ByteBuf payload, String fieldName) {
        requireReadable(payload, 1, fieldName);
        return payload.readUnsignedByte();
    }

    private static void ensureFullyConsumed(ByteBuf payload) {
        // 데이터가 남았다는 것은 sender와 receiver의 payload 계약이 어긋났다는 뜻이다.
        if (payload.isReadable()) {
            throw new IllegalArgumentException("payload contains unexpected trailing bytes");
        }
    }

    private static void requireReadable(ByteBuf payload, int requiredBytes, String fieldName) {
        if (payload.readableBytes() < requiredBytes) {
            throw new IllegalArgumentException("payload missing field: " + fieldName);
        }
    }
}
