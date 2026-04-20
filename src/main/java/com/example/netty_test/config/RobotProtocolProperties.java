package com.example.netty_test.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "robot.protocol")
public class RobotProtocolProperties {
    private int maxPayloadLength = 65_536;
}
