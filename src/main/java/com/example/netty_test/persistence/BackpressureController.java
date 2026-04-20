package com.example.netty_test.persistence;

import com.example.netty_test.config.PersistenceProperties;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class BackpressureController {
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final PersistenceProperties persistenceProperties;
    private final AtomicBoolean autoReadPaused = new AtomicBoolean(false);

    public BackpressureController(PersistenceProperties persistenceProperties) {
        this.persistenceProperties = persistenceProperties;
    }

    public void register(Channel channel) {
        channels.add(channel);
        if (autoReadPaused.get()) {
            applyAutoRead(channel, false);
        }
    }

    public void unregister(Channel channel) {
        channels.remove(channel);
    }

    public void onQueueDepthChanged(int depth) {
        // DB 저장이 밀리기 시작하면 채널 전체의 AUTO_READ를 내려서 입력 자체를 늦춘다.
        if (!autoReadPaused.get() && depth >= persistenceProperties.getHighWatermark()) {
            if (autoReadPaused.compareAndSet(false, true)) {
                setAutoReadForAll(false);
            }
        } else if (autoReadPaused.get() && depth <= persistenceProperties.getLowWatermark()) {
            if (autoReadPaused.compareAndSet(true, false)) {
                setAutoReadForAll(true);
            }
        }
    }

    private void setAutoReadForAll(boolean autoRead) {
        for (Channel channel : channels) {
            applyAutoRead(channel, autoRead);
        }
    }

    private void applyAutoRead(Channel channel, boolean autoRead) {
        channel.eventLoop().execute(() -> channel.config().setAutoRead(autoRead));
    }
}
