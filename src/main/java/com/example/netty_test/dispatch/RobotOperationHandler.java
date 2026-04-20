package com.example.netty_test.dispatch;

import com.example.netty_test.protocol.RobotAck;
import com.example.netty_test.protocol.RobotFrame;

public interface RobotOperationHandler {
    boolean supports(byte robotType, byte opCode);

    RobotAck handle(RobotFrame frame);
}
