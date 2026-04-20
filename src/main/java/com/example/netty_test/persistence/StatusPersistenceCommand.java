package com.example.netty_test.persistence;

import com.example.netty_test.protocol.RobotConstants;

import java.time.Instant;

public record StatusPersistenceCommand(
        byte robotType,
        String robotId,
        Instant occurredAt,
        int statusCode,
        int batteryPercent
) implements PersistenceCommand {
    @Override
    public byte opCode() {
        return RobotConstants.STATUS_OP_CODE;
    }
}
