package com.example.netty_test.robot;

import com.example.netty_test.application.RobotPayloadReader;
import com.example.netty_test.application.RobotPositionPayload;
import com.example.netty_test.dispatch.RobotOperationHandler;
import com.example.netty_test.persistence.PersistenceQueue;
import com.example.netty_test.persistence.PositionPersistenceCommand;
import com.example.netty_test.protocol.RobotAck;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.protocol.RobotFrame;

public abstract class AbstractPositionOperationHandler implements RobotOperationHandler {
    private final byte supportedRobotType;
    private final PersistenceQueue persistenceQueue;

    protected AbstractPositionOperationHandler(byte supportedRobotType, PersistenceQueue persistenceQueue) {
        this.supportedRobotType = supportedRobotType;
        this.persistenceQueue = persistenceQueue;
    }

    @Override
    public boolean supports(byte robotType, byte opCode) {
        return robotType == supportedRobotType && opCode == RobotConstants.POSITION_OP_CODE;
    }

    @Override
    public RobotAck handle(RobotFrame frame) {
        RobotPositionPayload payload = RobotPayloadReader.readPosition(frame.getPayload().duplicate());
        boolean accepted = persistenceQueue.offer(new PositionPersistenceCommand(
                frame.getRobotType(),
                payload.robotId(),
                payload.occurredAt(),
                payload.x(),
                payload.y(),
                payload.headingDeg()
        ));

        return accepted
                ? RobotAck.success(frame.getRobotType(), frame.getOpCode())
                : RobotAck.busy(frame.getRobotType(), frame.getOpCode());
    }
}
