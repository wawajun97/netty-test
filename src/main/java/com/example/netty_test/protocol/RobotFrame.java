package com.example.netty_test.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import lombok.Getter;

import java.time.Instant;

@Getter
public class RobotFrame implements ReferenceCounted {
    private final int dataSize;
    private final byte robotType;
    private final byte opCode;
    private final ByteBuf payload;
    private final Instant receivedAt;

    public RobotFrame(int dataSize, byte robotType, byte opCode, ByteBuf payload, Instant receivedAt) {
        this.dataSize = dataSize;
        this.robotType = robotType;
        this.opCode = opCode;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    @Override
    public int refCnt() {
        return payload.refCnt();
    }

    @Override
    public ReferenceCounted retain() {
        payload.retain();
        return this;
    }

    @Override
    public ReferenceCounted retain(int increment) {
        payload.retain(increment);
        return this;
    }

    @Override
    public ReferenceCounted touch() {
        payload.touch();
        return this;
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        payload.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return payload.release();
    }

    @Override
    public boolean release(int decrement) {
        return payload.release(decrement);
    }
}
