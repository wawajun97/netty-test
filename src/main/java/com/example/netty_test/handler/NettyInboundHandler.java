package com.example.netty_test.handler;

import com.example.netty_test.service.FileService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
@ChannelHandler.Sharable
public class NettyInboundHandler extends ChannelInboundHandlerAdapter {
    private final FileService fileService;

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 연결: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("채널 연결 해제: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            ReferenceCountUtil.release(msg);
            return;
        }

        ByteBuf byteBuf = (ByteBuf) msg;
        
        try {
            // 1. 파일명 길이 확인 (최소 4바이트 헤더 필요)
            if (byteBuf.readableBytes() < 4) {
                log.error("잘못된 데이터 포맷: 헤더 데이터 부족");
                return;
            }

            int fileNameSize = byteBuf.readInt();
            if (byteBuf.readableBytes() < fileNameSize) {
                log.error("잘못된 데이터 포맷: 파일명 데이터 부족 (기대: {}, 실제: {})", fileNameSize, byteBuf.readableBytes());
                return;
            }

            // 2. 파일명 추출 (UTF-8 명시)
            byte[] fNameBytes = new byte[fileNameSize];
            byteBuf.readBytes(fNameBytes);
            String fileName = new String(fNameBytes, StandardCharsets.UTF_8);

            // 3. 남은 데이터를 모두 파일 데이터로 읽음
            int fileDataSize = byteBuf.readableBytes();
            byte[] fileData = new byte[fileDataSize];
            byteBuf.readBytes(fileData);

            log.info("수신 파일명: {}, 크기: {} bytes", fileName, fileDataSize);

            // 4. 서비스 호출 (블로킹 작업은 NettyChannelInitializer의 EventExecutorGroup에서 처리됨)
            boolean isSuccess = fileService.insertFile(fileName, fileData);

            // 5. 처리 결과 응답
            ctx.writeAndFlush(Unpooled.copiedBuffer(String.valueOf(isSuccess), CharsetUtil.UTF_8));
            
        } catch (Exception e) {
            log.error("파일 처리 중 오류 발생: {}", e.getMessage(), e);
            ctx.writeAndFlush(Unpooled.copiedBuffer("false", CharsetUtil.UTF_8));
        } finally {
            // 사용이 끝난 버퍼 해제
            ReferenceCountUtil.release(byteBuf);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Netty 파이프라인 예외 발생: {}", cause.getMessage(), cause);
        ctx.close();
    }
}
