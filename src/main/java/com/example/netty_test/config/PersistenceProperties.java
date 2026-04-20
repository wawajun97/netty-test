package com.example.netty_test.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "robot.persistence")
public class PersistenceProperties {
    private int queueCapacity = 10_000;
    private int highWatermark = 8_000;
    private int lowWatermark = 4_000;
    private int batchSize = 200;
    private long flushIntervalMs = 100L;
}
