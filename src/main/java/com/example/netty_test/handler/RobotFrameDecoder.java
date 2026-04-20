package com.example.netty_test.handler;

import com.example.netty_test.config.RobotProtocolProperties;
import com.example.netty_test.metrics.RobotMetrics;
import com.example.netty_test.protocol.RobotAckResultCode;
import com.example.netty_test.protocol.RobotConstants;
import com.example.netty_test.protocol.RobotFrame;
import com.example.netty_test.protocol.RobotProtocolError;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.time.Instant;
import java.util.List;

public class RobotFrameDecoder extends ByteToMessageDecoder {
    private static final int MIN_FRAME_LENGTH = 1 + 4 + 1 + 1 + 1;

    private final int maxPayloadLength;
    private final RobotMetrics robotMetrics;

    public RobotFrameDecoder(RobotProtocolProperties robotProtocolProperties, RobotMetrics robotMetrics) {
        this.maxPayloadLength = robotProtocolProperties.getMaxPayloadLength();
        this.robotMetrics = robotMetrics;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            // 깨진 데이터가 앞에 섞여 들어와도 다음 STX부터 프레임 복구를 시도한다.
            int stxIndex = findStx(in);
            if (stxIndex < 0) {
                in.skipBytes(in.readableBytes());
                return;
            }

            if (stxIndex > in.readerIndex()) {
                in.skipBytes(stxIndex - in.readerIndex());
            }

            if (in.readableBytes() < MIN_FRAME_LENGTH) {
                return;
            }

            in.markReaderIndex();
            in.readByte();
            int dataSize = in.readInt();

            if (dataSize < 0 || dataSize > maxPayloadLength) {
                robotMetrics.incrementDecodeFailure("invalid_data_size");
                byte robotType = in.readableBytes() >= 1 ? in.getByte(in.readerIndex()) : 0;
                byte opCode = in.readableBytes() >= 2 ? in.getByte(in.readerIndex() + 1) : 0;
                out.add(new RobotProtocolError(robotType, opCode, RobotAckResultCode.INVALID_FRAME, "INVALID_DATA_SIZE"));
                in.resetReaderIndex();
                in.readByte();
                continue;
            }

            // STX와 dataSize는 이미 읽었으므로 나머지 본문(robotType/opCode/payload/ETX) 길이만 확인한다.
            int remainingFrameLength = 1 + 1 + dataSize + 1;
            if (in.readableBytes() < remainingFrameLength) {
                in.resetReaderIndex();
                return;
            }

            byte robotType = in.readByte();
            byte opCode = in.readByte();
            // payload는 retain slice로 넘겨서 불필요한 복사를 피한다.
            ByteBuf payload = in.readRetainedSlice(dataSize);
            byte etx = in.readByte();

            if (etx != RobotConstants.ETX) {
                payload.release();
                robotMetrics.incrementDecodeFailure("invalid_etx");
                out.add(new RobotProtocolError(robotType, opCode, RobotAckResultCode.INVALID_FRAME, "INVALID_ETX"));
                continue;
            }

            robotMetrics.incrementFrameReceived(robotType, opCode);
            out.add(new RobotFrame(dataSize, robotType, opCode, payload, Instant.now()));
        }
    }

    private int findStx(ByteBuf in) {
        for (int index = in.readerIndex(); index < in.writerIndex(); index++) {
            if (in.getByte(index) == RobotConstants.STX) {
                return index;
            }
        }
        return -1;
    }
}
