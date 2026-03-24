package com.example.netty_test.handler;

import com.example.netty_test.service.FileService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class NettyInboundHandler extends ChannelInboundHandlerAdapter {
    private final FileService fileService;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 연결");
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 연결 해제");
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws IOException {
        // LengthFieldBasedFrameDecoder가 완전한 프레임을 만들어주므로,
        // 별도의 버퍼에 누적할 필요 없이 바로 사용하면 됩니다.
        ByteBuf byteBuf = (ByteBuf) msg;
        
        try {
            int fileNameSize = byteBuf.readInt();

            byte[] fName = new byte[fileNameSize];
            byteBuf.readBytes(fName);

            String fileName = new String(fName);

            // 남은 데이터를 모두 파일 데이터로 읽음
            byte[] fileData = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(fileData);

            log.info("fileName : {}", fileName);

            boolean isSuccess = fileService.insertFile(fileName, fileData);

            ctx.channel().writeAndFlush(Unpooled.copiedBuffer(String.valueOf(isSuccess), CharsetUtil.UTF_8));
        } finally {
            // 사용이 끝난 버퍼는 반드시 해제해야 메모리 누수가 발생하지 않습니다.
            // 예외가 발생하더라도 실행되도록 finally 블록에 위치시킵니다.
            if (byteBuf.refCnt() > 0) {
                byteBuf.release();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.info("예외 발생");
        cause.printStackTrace();
        ctx.close();
    }
}
