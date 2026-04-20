package com.example.netty_test.persistence;

import com.example.netty_test.config.PersistenceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class PersistenceWorker implements SmartLifecycle {
    private final PersistenceQueue persistenceQueue;
    private final PersistenceJdbcRepository persistenceJdbcRepository;
    private final PersistenceProperties persistenceProperties;
    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "robot-persistence-worker");
        thread.setDaemon(true);
        return thread;
    });

    private volatile boolean running;

    public PersistenceWorker(
            PersistenceQueue persistenceQueue,
            PersistenceJdbcRepository persistenceJdbcRepository,
            PersistenceProperties persistenceProperties
    ) {
        this.persistenceQueue = persistenceQueue;
        this.persistenceJdbcRepository = persistenceJdbcRepository;
        this.persistenceProperties = persistenceProperties;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }

        // 짧은 주기로 큐를 비우면서 메시지당 DB write가 아니라 소량 배치 insert로 압축한다.
        executorService.scheduleWithFixedDelay(
                this::flushSafely,
                persistenceProperties.getFlushIntervalMs(),
                persistenceProperties.getFlushIntervalMs(),
                TimeUnit.MILLISECONDS
        );
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        flushSafely();
        executorService.shutdown();
        running = false;
        callback.run();
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
        return Integer.MAX_VALUE - 1;
    }

    private void flushSafely() {
        try {
            flushOnce();
        } catch (Exception e) {
            log.error("Persistence worker flush failed", e);
        }
    }

    private void flushOnce() {
        List<PersistenceCommand> drainedCommands = persistenceQueue.drain(persistenceProperties.getBatchSize());
        if (drainedCommands.isEmpty()) {
            return;
        }

        // 하나의 큐를 쓰되 테이블별 batch SQL은 분리하기 위해 타입별로 다시 묶는다.
        List<StatusPersistenceCommand> statuses = new ArrayList<>();
        List<PositionPersistenceCommand> positions = new ArrayList<>();

        for (PersistenceCommand command : drainedCommands) {
            if (command instanceof StatusPersistenceCommand status) {
                statuses.add(status);
            } else if (command instanceof PositionPersistenceCommand position) {
                positions.add(position);
            }
        }

        if (!statuses.isEmpty()) {
            persistenceJdbcRepository.saveStatuses(statuses);
        }
        if (!positions.isEmpty()) {
            persistenceJdbcRepository.savePositions(positions);
        }
    }
}
