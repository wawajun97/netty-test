package com.example.netty_test.application;

import java.time.Instant;

public record RobotPositionPayload(
        String robotId,
        Instant occurredAt,
        double x,
        double y,
        float headingDeg
) {
}
