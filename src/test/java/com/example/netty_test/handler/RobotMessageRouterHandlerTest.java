package com.example.netty_test.handler;

import com.example.netty_test.dispatch.RobotOperationDispatcher;
import com.example.netty_test.metrics.RobotMetrics;
import com.example.netty_test.persistence.PersistenceQueue;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.protocol.RobotFrame;
import com.example.netty_test.robot.typea.TypeAStatusHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RobotMessageRouterHandlerTest {
    @Test
    void queuesStatusMessageAndReturnsSuccessAck() {
        PersistenceQueue persistenceQueue = mock(PersistenceQueue.class);
        when(persistenceQueue.offer(ArgumentMatchers.any())).thenReturn(true);

        EmbeddedChannel channel = new EmbeddedChannel(
                new RobotAckEncoder(),
                new RobotMessageRouterHandler(
                        new RobotOperationDispatcher(List.of(new TypeAStatusHandler(persistenceQueue))),
                        new RobotMetrics(new SimpleMeterRegistry())
                )
        );

        channel.writeInbound(statusFrame());

        ByteBuf ack = channel.readOutbound();
        assertAck(ack, 0x00, RobotConstants.ROBOT_TYPE_A, RobotConstants.STATUS_OP_CODE);
        verify(persistenceQueue).offer(ArgumentMatchers.any());
        ack.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void returnsBusyWhenQueueIsFull() {
        PersistenceQueue persistenceQueue = mock(PersistenceQueue.class);
        when(persistenceQueue.offer(ArgumentMatchers.any())).thenReturn(false);

        EmbeddedChannel channel = new EmbeddedChannel(
                new RobotAckEncoder(),
                new RobotMessageRouterHandler(
                        new RobotOperationDispatcher(List.of(new TypeAStatusHandler(persistenceQueue))),
                        new RobotMetrics(new SimpleMeterRegistry())
                )
        );

        channel.writeInbound(statusFrame());

        ByteBuf ack = channel.readOutbound();
        assertAck(ack, 0x03, RobotConstants.ROBOT_TYPE_A, RobotConstants.STATUS_OP_CODE);
        ack.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void returnsUnsupportedWhenRouteDoesNotExist() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new RobotAckEncoder(),
                new RobotMessageRouterHandler(
                        new RobotOperationDispatcher(List.of()),
                        new RobotMetrics(new SimpleMeterRegistry())
                )
        );

        ByteBuf payload = Unpooled.buffer();
        RobotFrame frame = new RobotFrame(0, RobotConstants.ROBOT_TYPE_B, RobotConstants.POSITION_OP_CODE, payload, Instant.now());

        channel.writeInbound(frame);

        ByteBuf ack = channel.readOutbound();
        assertAck(ack, 0x02, RobotConstants.ROBOT_TYPE_B, RobotConstants.POSITION_OP_CODE);
        ack.release();
        channel.finishAndReleaseAll();
    }

    private RobotFrame statusFrame() {
        byte[] robotIdBytes = "robot-a".getBytes(StandardCharsets.UTF_8);
        ByteBuf payload = Unpooled.buffer();
        payload.writeShort(robotIdBytes.length);
        payload.writeBytes(robotIdBytes);
        payload.writeLong(System.currentTimeMillis());
        payload.writeByte(1);
        payload.writeByte(88);
        return new RobotFrame(payload.readableBytes(), RobotConstants.ROBOT_TYPE_A, RobotConstants.STATUS_OP_CODE, payload, Instant.now());
    }

    private void assertAck(ByteBuf ack, int resultCode, byte robotType, byte requestOpCode) {
        assertThat(ack.readByte()).isEqualTo(RobotConstants.STX);
        int dataSize = ack.readInt();
        assertThat(dataSize).isGreaterThanOrEqualTo(4);
        assertThat(ack.readByte()).isEqualTo(robotType);
        assertThat(ack.readByte()).isEqualTo(RobotConstants.ACK_OP_CODE);
        assertThat((int) ack.readUnsignedByte()).isEqualTo(resultCode);
        assertThat(ack.readByte()).isEqualTo(requestOpCode);
    }
}
