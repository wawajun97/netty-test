package com.example.netty_test.handler;

import com.example.netty_test.service.FileService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class NettyInboundHandler extends ChannelInboundHandlerAdapter {
    private final FileService fileService;


    private final AttributeKey<ByteBuf> BYTEBUF_ATTRIBUTE = AttributeKey.newInstance("ByteBufAttribute");
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 연결");
        setBytBuf(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 연결 해제");
        Attribute<ByteBuf> attr = ctx.channel().attr(BYTEBUF_ATTRIBUTE);
        ByteBuf myByteBuf = attr.get();
        if (myByteBuf != null) {
            myByteBuf.release(); // 사용이 끝난 버퍼 해제
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws IOException {
        Attribute<ByteBuf> attr = ctx.channel().attr(BYTEBUF_ATTRIBUTE);
        ByteBuf byteBuf = attr.get();
        ByteBuf receivedBuf = (ByteBuf) msg;

        if (byteBuf == null) {
            byteBuf = setBytBuf(ctx);
        }

        byteBuf.writeBytes(receivedBuf);
        receivedBuf.release();

        int fileNameSize = byteBuf.readInt();

        byte[] fName = new byte[fileNameSize];
        byteBuf.readBytes(fName);

        String fileName = new String(fName);

        byte[] fileData = new byte[byteBuf.readableBytes()];
        byteBuf.readBytes(fileData);

        log.info("fileName : {}", fileName);

        boolean isSuccess = fileService.insertFile(fileName, fileData);

        ctx.channel().writeAndFlush(Unpooled.copiedBuffer(String.valueOf(isSuccess), CharsetUtil.UTF_8));

//        byte code = byteBuf.readByte();
//        byte[] dataArr = new byte[byteBuf.readableBytes()];
//        byteBuf.readBytes(dataArr);
//
//        log.info("code : {}, data : {}", code, dataArr);
//
//        parseService.parseData(code, dataArr);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.info("예외 발생");
        cause.printStackTrace();
        ctx.close();
    }

    private ByteBuf setBytBuf(ChannelHandlerContext ctx) {
        Channel channel = ctx.channel();
        Attribute<ByteBuf> attr = channel.attr(BYTEBUF_ATTRIBUTE);
        // Attribute에 ByteBuf 저장
        ByteBuf myByteBuf = ctx.alloc().buffer();
        attr.set(myByteBuf);

        return myByteBuf;
    }
}
