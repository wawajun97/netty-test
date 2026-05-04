package com.example.netty_test.common;

import com.example.netty_test.config.NettyProperties;
import com.example.netty_test.config.RobotProtocolProperties;
import com.example.netty_test.handler.RobotAckEncoder;
import com.example.netty_test.handler.RobotChannelStateHandler;
import com.example.netty_test.handler.RobotFrameDecoder;
import com.example.netty_test.handler.RobotMessageRouterHandler;
import com.example.netty_test.metrics.RobotMetrics;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final RobotProtocolProperties robotProtocolProperties;
    private final NettyProperties nettyProperties;
    private final RobotMetrics robotMetrics;
    private final RobotAckEncoder robotAckEncoder;
    private final RobotMessageRouterHandler robotMessageRouterHandler;
    private final RobotChannelStateHandler robotChannelStateHandler;

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        // 연결 유휴 상태를 감시해서 죽은 세션이 계속 남지 않게 한다.
        pipeline.addLast(new IdleStateHandler(nettyProperties.getIdleSeconds(), 0, 0));
        // 연결 등록/해제, writability, idle close 같은 채널 상태 관리를 담당한다.
        pipeline.addLast(robotChannelStateHandler);
        // STX/ETX 기반 커스텀 바이너리 프로토콜을 RobotFrame으로 변환한다.
        pipeline.addLast(new RobotFrameDecoder(robotProtocolProperties, robotMetrics));
        // 비즈니스 처리 결과인 RobotAck를 TCP 응답 바이트로 인코딩한다.
        // outbound 이벤트는 pipeline을 뒤에서 앞으로 통과하므로 RobotMessageRouterHandler가 생성한 ACK를 인코딩하려면 RobotAckEncoder가 그 앞에 있어야 한다.
        pipeline.addLast(robotAckEncoder);
        // robotType/opCode 조합으로 실제 비즈니스 핸들러를 찾아 실행한다.
        pipeline.addLast(robotMessageRouterHandler);
    }
}
