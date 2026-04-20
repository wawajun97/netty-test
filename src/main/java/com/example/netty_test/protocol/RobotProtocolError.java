package com.example.netty_test.protocol;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RobotProtocolError {
    private final byte robotType;
    private final byte opCode;
    private final RobotAckResultCode resultCode;
    private final String message;
}
