package com.example.netty_test.robot.typea;

import com.example.netty_test.persistence.PersistenceQueue;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.robot.AbstractPositionOperationHandler;
import org.springframework.stereotype.Component;

@Component
public class TypeAPositionHandler extends AbstractPositionOperationHandler {
    public TypeAPositionHandler(PersistenceQueue persistenceQueue) {
        super(RobotConstants.ROBOT_TYPE_A, persistenceQueue);
    }
}
