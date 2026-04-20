package com.example.netty_test.handler;

import com.example.netty_test.config.RobotProtocolProperties;
import com.example.netty_test.metrics.RobotMetrics;
import com.example.netty_test.protocol.RobotAckResultCode;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.protocol.RobotFrame;
import com.example.netty_test.protocol.RobotProtocolError;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RobotFrameDecoderTest {
    @Test
    void decodesStatusFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(newDecoder(256));
        ByteBuf frame = buildFrame(RobotConstants.ROBOT_TYPE_A, RobotConstants.STATUS_OP_CODE, statusPayload("robot-a"));

        channel.writeInbound(frame);

        RobotFrame decoded = channel.readInbound();
        assertThat(decoded).isNotNull();
        assertThat(decoded.getRobotType()).isEqualTo(RobotConstants.ROBOT_TYPE_A);
        assertThat(decoded.getOpCode()).isEqualTo(RobotConstants.STATUS_OP_CODE);
        assertThat(decoded.getDataSize()).isPositive();
        decoded.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void emitsProtocolErrorWhenEtxIsInvalid() {
        EmbeddedChannel channel = new EmbeddedChannel(newDecoder(256));
        ByteBuf frame = buildFrame(RobotConstants.ROBOT_TYPE_A, RobotConstants.STATUS_OP_CODE, statusPayload("robot-a"));
        frame.setByte(frame.writerIndex() - 1, 0x04);

        channel.writeInbound(frame);

        RobotProtocolError error = channel.readInbound();
        assertThat(error).isNotNull();
        assertThat(error.getResultCode()).isEqualTo(RobotAckResultCode.INVALID_FRAME);
        channel.finishAndReleaseAll();
    }

    @Test
    void emitsProtocolErrorWhenPayloadExceedsLimit() {
        EmbeddedChannel channel = new EmbeddedChannel(newDecoder(4));
        ByteBuf payload = Unpooled.copiedBuffer("12345", StandardCharsets.UTF_8);
        ByteBuf frame = buildFrame(RobotConstants.ROBOT_TYPE_A, RobotConstants.STATUS_OP_CODE, payload);

        channel.writeInbound(frame);

        RobotProtocolError error = channel.readInbound();
        assertThat(error).isNotNull();
        assertThat(error.getResultCode()).isEqualTo(RobotAckResultCode.INVALID_FRAME);
        channel.finishAndReleaseAll();
    }

    private RobotFrameDecoder newDecoder(int maxPayloadLength) {
        RobotProtocolProperties properties = new RobotProtocolProperties();
        properties.setMaxPayloadLength(maxPayloadLength);
        return new RobotFrameDecoder(properties, new RobotMetrics(new SimpleMeterRegistry()));
    }

    private ByteBuf buildFrame(byte robotType, byte opCode, ByteBuf payload) {
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(RobotConstants.STX);
        frame.writeInt(payload.readableBytes());
        frame.writeByte(robotType);
        frame.writeByte(opCode);
        frame.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
        frame.writeByte(RobotConstants.ETX);
        payload.release();
        return frame;
    }

    private ByteBuf statusPayload(String robotId) {
        byte[] robotIdBytes = robotId.getBytes(StandardCharsets.UTF_8);
        ByteBuf payload = Unpooled.buffer();
        payload.writeShort(robotIdBytes.length);
        payload.writeBytes(robotIdBytes);
        payload.writeLong(System.currentTimeMillis());
        payload.writeByte(1);
        payload.writeByte(90);
        return payload;
    }
}
