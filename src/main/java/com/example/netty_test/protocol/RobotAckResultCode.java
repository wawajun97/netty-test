package com.example.netty_test.protocol;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RobotAckResultCode {
    SUCCESS((byte) 0x00, "QUEUED"),
    INVALID_FRAME((byte) 0x01, "INVALID_FRAME"),
    UNSUPPORTED_ROUTE((byte) 0x02, "UNSUPPORTED_ROUTE"),
    BUSY((byte) 0x03, "BUSY"),
    INTERNAL_ERROR((byte) 0x04, "INTERNAL_ERROR"),
    INVALID_PAYLOAD((byte) 0x05, "INVALID_PAYLOAD");

    private final byte code;
    private final String defaultMessage;
}
