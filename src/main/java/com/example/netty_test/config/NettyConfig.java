package com.example.netty_test.config;

import com.example.netty_test.common.NettyChannelInitializer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NettyConfig {
    @Value("${netty.boss.count}")
    private int bossCount;

    @Value("${netty.worker.count}")
    private int workerCount;

    @Bean
    public ServerBootstrap serverBootstrap(NettyChannelInitializer nettyChannelInitializer) throws Exception {
        //서버 설정을 도와주는 객체
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup(), workerGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(nettyChannelInitializer)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        return b;
    }

    //클라이언트와의 연결을 도와주는 eventLoop
    @Bean(destroyMethod = "shutdownGracefully") //프로젝트가 종료될 때 호출하여 안전하게 종료되도록 도와줌
    public NioEventLoopGroup bossGroup() {
        return new NioEventLoopGroup(bossCount);
    }

    //연결된 cannel을 boss에게 받아서 트래픽을 관리하는 eventLoop
    @Bean(destroyMethod = "shutdownGracefully")
    public NioEventLoopGroup workerGroup() {
        return new NioEventLoopGroup(workerCount);
    }
}
