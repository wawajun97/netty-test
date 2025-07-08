package com.example.netty_test.common;

import com.example.netty_test.handler.NettyInboundHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {
    private final NettyInboundHandler nettyInboundHandler;

    //새로운 연결이 들어올 때마다 pipeline에 handler 등록
    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new LengthFieldBasedFrameDecoder(1024,0,4,0,4));
        pipeline.addLast(nettyInboundHandler);
    }
}
