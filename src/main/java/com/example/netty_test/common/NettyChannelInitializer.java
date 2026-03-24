package com.example.netty_test.common;

import com.example.netty_test.handler.NettyInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final NettyInboundHandler nettyInboundHandler;
    
    // 블로킹 작업을 처리하기 위한 별도의 스레드 그룹 생성 (스레드 수 10개 예시)
    private final EventExecutorGroup group = new DefaultEventExecutorGroup(10);

    //새로운 연결이 들어올 때마다 pipeline에 handler 등록
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new LengthFieldBasedFrameDecoder(1024*1024*1024,0,4,0,4));
        
        // nettyInboundHandler를 별도의 EventExecutorGroup에서 실행하도록 등록
        pipeline.addLast(group, nettyInboundHandler);
    }
}
