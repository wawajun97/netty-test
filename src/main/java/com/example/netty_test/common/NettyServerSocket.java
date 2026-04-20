package com.example.netty_test.common;

import com.example.netty_test.config.NettyProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class NettyServerSocket implements SmartLifecycle {
    private final ServerBootstrap serverBootstrap;
    private final NettyProperties nettyProperties;

    private Channel serverChannel;
    private volatile boolean running;

    @Override
    public void start() {
        if (running || !nettyProperties.isEnabled()) {
            return;
        }

        try {
            // SmartLifecycle로 서버를 붙이면 테스트에서는 disabled 처리만으로 자동기동을 끌 수 있다.
            ChannelFuture serverChannelFuture = serverBootstrap.bind(nettyProperties.getPort()).sync();
            serverChannel = serverChannelFuture.channel();
            running = true;
            log.info("Netty server started on port {}", nettyProperties.getPort());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Netty server", e);
        }
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        if (serverChannel != null) {
            // closeFuture().sync()로 메인 스레드를 붙잡지 않고, 종료 시점에만 채널을 닫는다.
            serverChannel.close().awaitUninterruptibly();
            serverChannel = null;
        }
        running = false;
        callback.run();
        log.info("Netty server stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @PreDestroy
    public void destroy() {
        stop();
    }
}
