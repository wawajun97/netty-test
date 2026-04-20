package com.example.netty_test.config;

import com.example.netty_test.common.NettyChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NettyConfig {
    @Bean
    public ServerBootstrap serverBootstrap(
            NettyProperties nettyProperties,
            NettyChannelInitializer nettyChannelInitializer,
            NioEventLoopGroup bossGroup,
            NioEventLoopGroup workerGroup
    ) {
        return new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(nettyChannelInitializer)
                .option(ChannelOption.SO_BACKLOG, nettyProperties.getSoBacklog())
                .childOption(ChannelOption.SO_KEEPALIVE, nettyProperties.isKeepAlive())
                .childOption(ChannelOption.TCP_NODELAY, true)
                // 작은 메시지를 많이 처리하므로 pooled/direct allocator를 기본값으로 사용한다.
                .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .childOption(
                        ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(
                                nettyProperties.getWriteBufferLowWatermark(),
                                nettyProperties.getWriteBufferHighWatermark()
                        )
                );
    }

    @Bean(destroyMethod = "shutdownGracefully")
    public NioEventLoopGroup bossGroup(NettyProperties nettyProperties) {
        return new NioEventLoopGroup(nettyProperties.getBossCount());
    }

    @Bean(destroyMethod = "shutdownGracefully")
    public NioEventLoopGroup workerGroup(NettyProperties nettyProperties) {
        return new NioEventLoopGroup(nettyProperties.getWorkerCount());
    }
}
