package com.example.netty_test.protocol;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RobotAck {
    private final byte robotType;
    private final RobotAckResultCode resultCode;
    private final byte requestOpCode;
    private final String message;

    public static RobotAck success(byte robotType, byte requestOpCode) {
        return new RobotAck(robotType, RobotAckResultCode.SUCCESS, requestOpCode, RobotAckResultCode.SUCCESS.getDefaultMessage());
    }

    public static RobotAck busy(byte robotType, byte requestOpCode) {
        return new RobotAck(robotType, RobotAckResultCode.BUSY, requestOpCode, RobotAckResultCode.BUSY.getDefaultMessage());
    }

    public static RobotAck unsupported(byte robotType, byte requestOpCode) {
        return new RobotAck(robotType, RobotAckResultCode.UNSUPPORTED_ROUTE, requestOpCode, RobotAckResultCode.UNSUPPORTED_ROUTE.getDefaultMessage());
    }

    public static RobotAck invalidFrame(byte robotType, byte requestOpCode, String message) {
        return new RobotAck(robotType, RobotAckResultCode.INVALID_FRAME, requestOpCode, message);
    }

    public static RobotAck invalidPayload(byte robotType, byte requestOpCode, String message) {
        return new RobotAck(robotType, RobotAckResultCode.INVALID_PAYLOAD, requestOpCode, message);
    }

    public static RobotAck internalError(byte robotType, byte requestOpCode) {
        return new RobotAck(robotType, RobotAckResultCode.INTERNAL_ERROR, requestOpCode, RobotAckResultCode.INTERNAL_ERROR.getDefaultMessage());
    }
}
