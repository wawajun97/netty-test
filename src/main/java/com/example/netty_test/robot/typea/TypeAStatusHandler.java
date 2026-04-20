package com.example.netty_test.robot.typea;

import com.example.netty_test.persistence.PersistenceQueue;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.robot.AbstractStatusOperationHandler;
import org.springframework.stereotype.Component;

@Component
public class TypeAStatusHandler extends AbstractStatusOperationHandler {
    public TypeAStatusHandler(PersistenceQueue persistenceQueue) {
        super(RobotConstants.ROBOT_TYPE_A, persistenceQueue);
    }
}
