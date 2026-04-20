package com.example.netty_test.protocol;

public final class RobotConstants {
    public static final byte STX = 0x02;
    public static final byte ETX = 0x03;
    public static final byte ACK_OP_CODE = 0x7F;

    public static final byte ROBOT_TYPE_A = 0x01;
    public static final byte ROBOT_TYPE_B = 0x02;

    public static final byte STATUS_OP_CODE = 0x01;
    public static final byte POSITION_OP_CODE = 0x02;

    private RobotConstants() {
    }
}
