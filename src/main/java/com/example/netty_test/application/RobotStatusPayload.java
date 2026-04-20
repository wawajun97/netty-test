package com.example.netty_test.application;

import java.time.Instant;

public record RobotStatusPayload(
        String robotId,
        Instant occurredAt,
        int statusCode,
        int batteryPercent
) {
}
