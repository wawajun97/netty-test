package com.example.netty_test.dispatch;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
* 서버 시작 시 모든 robotType과 opCode를 기준으로 헨들러 등록
* enum 타입으로 robotType, opCode 관리도 가능
*/
@Component
public class RobotOperationDispatcher {
    private final Map<RoutingKey, RobotOperationHandler> handlers;

    public RobotOperationDispatcher(List<RobotOperationHandler> handlers) {
        Map<RoutingKey, RobotOperationHandler> resolvedHandlers = new HashMap<>();
        // supports(robotType, opCode) 규칙을 부팅 시점에 모두 펼쳐서 런타임 lookup 비용을 단순화한다.
        for (RobotOperationHandler handler : handlers) {
            for (int robotType = 0; robotType <= 0xFF; robotType++) {
                for (int opCode = 0; opCode <= 0xFF; opCode++) {
                    byte resolvedRobotType = (byte) robotType;
                    byte resolvedOpCode = (byte) opCode;
                    if (handler.supports(resolvedRobotType, resolvedOpCode)) {
                        RoutingKey key = new RoutingKey(resolvedRobotType, resolvedOpCode);
                        if (resolvedHandlers.putIfAbsent(key, handler) != null) {
                            throw new IllegalStateException("Duplicate handler for " + key);
                        }
                    }
                }
            }
        }
        this.handlers = Map.copyOf(resolvedHandlers);
    }

    public RobotOperationHandler getHandler(byte robotType, byte opCode) {
        return handlers.get(new RoutingKey(robotType, opCode));
    }
}
