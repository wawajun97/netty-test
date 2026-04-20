package com.example.netty_test.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "robot.netty")
public class NettyProperties {
    private boolean enabled = true;
    private int port = 29001;
    private int bossCount = 1;
    private int workerCount = 10;
    private int soBacklog = 1024;
    private boolean keepAlive = true;
    private int idleSeconds = 60;
    private int writeBufferLowWatermark = 32 * 1024;
    private int writeBufferHighWatermark = 128 * 1024;
}
