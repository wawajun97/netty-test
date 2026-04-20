package com.example.netty_test.persistence;

import com.example.netty_test.config.PersistenceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

@Component
public class PersistenceQueue {
    private final BlockingQueue<PersistenceCommand> queue;
    private final BackpressureController backpressureController;

    public PersistenceQueue(
            PersistenceProperties persistenceProperties,
            BackpressureController backpressureController,
            MeterRegistry meterRegistry
    ) {
        this.queue = new ArrayBlockingQueue<>(persistenceProperties.getQueueCapacity());
        this.backpressureController = backpressureController;
        meterRegistry.gauge("robot.persistence.queue.depth", queue, BlockingQueue::size);
    }

    public boolean offer(PersistenceCommand command) {
        // 큐 적재 성공 여부 자체가 ACK/BUSY 판단 기준이 된다.
        boolean accepted = queue.offer(command);
        backpressureController.onQueueDepthChanged(queue.size());
        return accepted;
    }

    public List<PersistenceCommand> drain(int maxSize) {
        List<PersistenceCommand> commands = new ArrayList<>(maxSize);
        queue.drainTo(commands, maxSize);
        backpressureController.onQueueDepthChanged(queue.size());
        return commands;
    }

    public int size() {
        return queue.size();
    }
}
