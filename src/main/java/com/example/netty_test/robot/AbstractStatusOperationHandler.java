package com.example.netty_test.robot;

import com.example.netty_test.application.RobotPayloadReader;
import com.example.netty_test.application.RobotStatusPayload;
import com.example.netty_test.dispatch.RobotOperationHandler;
import com.example.netty_test.persistence.PersistenceQueue;
import com.example.netty_test.persistence.StatusPersistenceCommand;
import com.example.netty_test.protocol.RobotAck;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.protocol.RobotFrame;

public abstract class AbstractStatusOperationHandler implements RobotOperationHandler {
    private final byte supportedRobotType;
    private final PersistenceQueue persistenceQueue;

    protected AbstractStatusOperationHandler(byte supportedRobotType, PersistenceQueue persistenceQueue) {
        this.supportedRobotType = supportedRobotType;
        this.persistenceQueue = persistenceQueue;
    }

    @Override
    public boolean supports(byte robotType, byte opCode) {
        return robotType == supportedRobotType && opCode == RobotConstants.STATUS_OP_CODE;
    }

    @Override
    public RobotAck handle(RobotFrame frame) {
        RobotStatusPayload payload = RobotPayloadReader.readStatus(frame.getPayload().duplicate());
        // ACK 성공은 DB 반영 완료가 아니라 "애플리케이션 내부 저장 큐에 적재 성공"을 의미한다.
        boolean accepted = persistenceQueue.offer(new StatusPersistenceCommand(
                frame.getRobotType(),
                payload.robotId(),
                payload.occurredAt(),
                payload.statusCode(),
                payload.batteryPercent()
        ));

        return accepted
                ? RobotAck.success(frame.getRobotType(), frame.getOpCode())
                : RobotAck.busy(frame.getRobotType(), frame.getOpCode());
    }
}
