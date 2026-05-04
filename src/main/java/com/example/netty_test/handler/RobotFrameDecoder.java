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
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Slf4j
public class RobotFrameDecoder extends ByteToMessageDecoder {
    private static final int MIN_FRAME_LENGTH = 1 + 4 + 1 + 1 + 1;
    private static final long NO_PARTIAL_FRAME = -1L;

    private final int maxPayloadLength;
    private final long partialFrameTimeoutNanos;
    private final RobotMetrics robotMetrics;
    private final LongSupplier nanoTimeSupplier;
    private long partialFrameStartedAtNanos = NO_PARTIAL_FRAME;

    public RobotFrameDecoder(RobotProtocolProperties robotProtocolProperties, RobotMetrics robotMetrics) {
        this(robotProtocolProperties, robotMetrics, System::nanoTime);
    }

    RobotFrameDecoder(
            RobotProtocolProperties robotProtocolProperties,
            RobotMetrics robotMetrics,
            LongSupplier nanoTimeSupplier
    ) {
        this.maxPayloadLength = robotProtocolProperties.getMaxPayloadLength();
        this.partialFrameTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(robotProtocolProperties.getPartialFrameTimeoutMs());
        this.robotMetrics = robotMetrics;
        this.nanoTimeSupplier = nanoTimeSupplier;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (true) {
            discardTimedOutPartialFrame(ctx, in, out);
            // 깨진 데이터가 앞에 섞여 들어와도 다음 STX부터 프레임 복구를 시도한다.
            int stxIndex = findStx(in);
            if (stxIndex < 0) {
                clearPartialFrameTracking();
                in.skipBytes(in.readableBytes());
                return;
            }

            if (stxIndex > in.readerIndex()) {
                in.skipBytes(stxIndex - in.readerIndex());
                clearPartialFrameTracking();
            }

            if (in.readableBytes() < MIN_FRAME_LENGTH) {
                markPartialFrameStartIfNeeded();
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
                clearPartialFrameTracking();
                in.resetReaderIndex();
                in.readByte();
                continue;
            }

            // STX와 dataSize는 이미 읽었으므로 나머지 본문(robotType/opCode/payload/ETX) 길이만 확인한다.
            int remainingFrameLength = 1 + 1 + dataSize + 1;
            if (in.readableBytes() < remainingFrameLength) {
                in.resetReaderIndex();
                markPartialFrameStartIfNeeded();
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
                clearPartialFrameTracking();
                continue;
            }

            clearPartialFrameTracking();
            robotMetrics.incrementFrameReceived(robotType, opCode);
            out.add(new RobotFrame(dataSize, robotType, opCode, payload, Instant.now()));
        }
    }

    private void discardTimedOutPartialFrame(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!hasTimedOutPartialFrame()) {
            return;
        }

        byte robotType = in.readableBytes() >= 6 ? in.getByte(in.readerIndex() + 5) : 0;
        byte opCode = in.readableBytes() >= 7 ? in.getByte(in.readerIndex() + 6) : 0;
        int nextStxIndex = findStx(in, in.readerIndex() + 1);
        int discardedBytes = nextStxIndex < 0 ? in.readableBytes() : nextStxIndex - in.readerIndex();

        robotMetrics.incrementDecodeFailure("partial_frame_timeout");
        log.warn(
                "Discarding timed out partial frame: remoteAddress={}, discardedBytes={}",
                ctx.channel().remoteAddress(),
                discardedBytes
        );
        out.add(new RobotProtocolError(robotType, opCode, RobotAckResultCode.INVALID_FRAME, "PARTIAL_FRAME_TIMEOUT"));

        if (nextStxIndex < 0) {
            in.skipBytes(in.readableBytes());
        } else {
            in.skipBytes(discardedBytes);
        }

        clearPartialFrameTracking();
    }

    private boolean hasTimedOutPartialFrame() {
        return partialFrameStartedAtNanos != NO_PARTIAL_FRAME
                && nanoTimeSupplier.getAsLong() - partialFrameStartedAtNanos >= partialFrameTimeoutNanos;
    }

    private void markPartialFrameStartIfNeeded() {
        if (partialFrameStartedAtNanos == NO_PARTIAL_FRAME) {
            partialFrameStartedAtNanos = nanoTimeSupplier.getAsLong();
        }
    }

    private void clearPartialFrameTracking() {
        partialFrameStartedAtNanos = NO_PARTIAL_FRAME;
    }

    private int findStx(ByteBuf in) {
        return findStx(in, in.readerIndex());
    }

    private int findStx(ByteBuf in, int fromIndex) {
        for (int index = fromIndex; index < in.writerIndex(); index++) {
            if (in.getByte(index) == RobotConstants.STX) {
                return index;
            }
        }
        return -1;
    }
}
