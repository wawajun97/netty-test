package com.example.netty_test.handler;

import com.example.netty_test.service.ParseService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NettyInboundHandler extends ChannelInboundHandlerAdapter {
    private final ParseService parseService;
    private ByteBuf byteBuf;
    private byte dataSize;
    private byte code;
    private final int HEADER_SIZE = 2;
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 등록");
        super.channelRegistered(ctx);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 삭제");
        super.channelUnregistered(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        byte[] message = (byte[]) msg;

        if(byteBuf.readableBytes() == 0) {
            code = byteBuf.readByte();
            dataSize = byteBuf.readByte();

            byteBuf.writeBytes(message);
        } else {
            byteBuf.writeBytes(message);
        }

        if(byteBuf.readableBytes() == dataSize) {
            byte[] dataArr = new byte[dataSize];
            byteBuf.readBytes(dataArr);

            parseService.parseData(code, dataArr);

            ctx.channel().writeAndFlush(Unpooled.copiedBuffer("data received", CharsetUtil.UTF_8));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.info("예외 발생");
        cause.printStackTrace();
        ctx.close();
    }
}
