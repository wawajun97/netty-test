package com.example.netty_test.handler;

import com.example.netty_test.persistence.BackpressureController;
import com.example.netty_test.protocol.RobotAck;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class RobotChannelStateHandler extends ChannelInboundHandlerAdapter {
    private final BackpressureController backpressureController;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // backpressure 상태일 때 새 채널도 즉시 auto-read 제어를 받도록 등록한다.
        backpressureController.register(ctx.channel());
        log.info("Channel connected: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        backpressureController.unregister(ctx.channel());
        log.info("Channel disconnected: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (!ctx.channel().isWritable()) {
            log.warn("Channel is not writable: {}", ctx.channel().remoteAddress());
        }
        super.channelWritabilityChanged(ctx);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            // 로봇 연결이 비정상적으로 남아있지 않도록 idle 세션을 정리한다.
            log.warn("Closing idle channel: {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Pipeline exception", cause);
        ctx.channel().writeAndFlush(RobotAck.internalError((byte) 0, (byte) 0)).addListener(future -> ctx.close());
    }
}
