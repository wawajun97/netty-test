package com.example.netty_test.robot.typeb;

import com.example.netty_test.persistence.PersistenceQueue;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.robot.AbstractStatusOperationHandler;
import org.springframework.stereotype.Component;

@Component
public class TypeBStatusHandler extends AbstractStatusOperationHandler {
    public TypeBStatusHandler(PersistenceQueue persistenceQueue) {
        super(RobotConstants.ROBOT_TYPE_B, persistenceQueue);
    }
}
