package com.example.netty_test.handler;

import com.example.netty_test.protocol.RobotAck;
import com.example.netty_test.protocol.RobotConstants;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ChannelHandler.Sharable
public class RobotAckEncoder extends MessageToByteEncoder<RobotAck> {
    @Override
    protected void encode(ChannelHandlerContext ctx, RobotAck msg, ByteBuf out) {
        byte[] messageBytes = msg.getMessage().getBytes(StandardCharsets.UTF_8);
        if (messageBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("Ack message is too large");
        }

        int dataSize = 1 + 1 + 2 + messageBytes.length;

        out.writeByte(RobotConstants.STX);
        out.writeInt(dataSize);
        out.writeByte(msg.getRobotType());
        out.writeByte(RobotConstants.ACK_OP_CODE);
        out.writeByte(msg.getResultCode().getCode());
        out.writeByte(msg.getRequestOpCode());
        out.writeShort(messageBytes.length);
        out.writeBytes(messageBytes);
        out.writeByte(RobotConstants.ETX);
    }
}
