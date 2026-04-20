package com.example.netty_test.persistence;

import java.time.Instant;

public interface PersistenceCommand {
    byte robotType();

    byte opCode();

    String robotId();

    Instant occurredAt();
}
