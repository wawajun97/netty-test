package com.example.netty_test.handler;

import com.example.netty_test.dispatch.RobotOperationDispatcher;
import com.example.netty_test.dispatch.RobotOperationHandler;
import com.example.netty_test.metrics.RobotMetrics;
import com.example.netty_test.protocol.RobotAck;
import com.example.netty_test.protocol.RobotFrame;
import com.example.netty_test.protocol.RobotProtocolError;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class RobotMessageRouterHandler extends ChannelInboundHandlerAdapter {
    private final RobotOperationDispatcher robotOperationDispatcher;
    private final RobotMetrics robotMetrics;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean shouldRelease = true;
        try {
            if (msg instanceof RobotProtocolError protocolError) {
                writeAck(ctx, RobotAck.invalidFrame(protocolError.getRobotType(), protocolError.getOpCode(), protocolError.getMessage()));
                return;
            }

            if (!(msg instanceof RobotFrame frame)) {
                shouldRelease = false;
                ctx.fireChannelRead(msg);
                return;
            }

            // Netty pipeline은 공통으로 유지하고, robotType/opCode 분기는 dispatcher에서 해결한다.
            RobotOperationHandler handler = robotOperationDispatcher.getHandler(frame.getRobotType(), frame.getOpCode());
            if (handler == null) {
                robotMetrics.incrementRouteMiss(frame.getRobotType(), frame.getOpCode());
                writeAck(ctx, RobotAck.unsupported(frame.getRobotType(), frame.getOpCode()));
                return;
            }

            RobotAck ack = handler.handle(frame);
            writeAck(ctx, ack);
        } catch (IllegalArgumentException e) {
            RobotFrame frame = msg instanceof RobotFrame ? (RobotFrame) msg : null;
            byte robotType = frame != null ? frame.getRobotType() : 0;
            byte opCode = frame != null ? frame.getOpCode() : 0;
            log.warn("Invalid payload received: {}", e.getMessage());
            writeAck(ctx, RobotAck.invalidPayload(robotType, opCode, e.getMessage()));
        } catch (Exception e) {
            RobotFrame frame = msg instanceof RobotFrame ? (RobotFrame) msg : null;
            byte robotType = frame != null ? frame.getRobotType() : 0;
            byte opCode = frame != null ? frame.getOpCode() : 0;
            log.error("Unexpected message handling failure", e);
            writeAck(ctx, RobotAck.internalError(robotType, opCode));
        } finally {
            // 디코더가 retain한 frame/payload는 여기서 수명 관리를 끝낸다.
            if (shouldRelease) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private void writeAck(ChannelHandlerContext ctx, RobotAck ack) {
        robotMetrics.incrementAck(ack.getResultCode(), ack.getRobotType(), ack.getRequestOpCode());
        ctx.writeAndFlush(ack);
    }
}
