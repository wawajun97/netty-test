package com.example.netty_test.persistence;

public record FailurePersistenceRecord(
        byte robotType,
        byte opCode,
        String robotId,
        String errorMessage
) {
}
