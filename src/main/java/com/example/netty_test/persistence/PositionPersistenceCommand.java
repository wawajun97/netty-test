package com.example.netty_test.persistence;

import com.example.netty_test.protocol.RobotConstants;

import java.time.Instant;

public record PositionPersistenceCommand(
        byte robotType,
        String robotId,
        Instant occurredAt,
        double x,
        double y,
        float headingDeg
) implements PersistenceCommand {
    @Override
    public byte opCode() {
        return RobotConstants.POSITION_OP_CODE;
    }
}
