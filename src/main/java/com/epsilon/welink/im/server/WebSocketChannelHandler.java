package com.epsilon.welink.im.server;

import com.epsilon.welink.im.service.IMService;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Sharable
@Component
public class WebSocketChannelHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final IMService imService;

    public WebSocketChannelHandler(IMService imService) {
        this.imService = imService;
    }

    // 处理通道激活事件，连接建立
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("WebSocket connection established: {}", ctx.channel().id());
    }

    // 处理通道不活动事件，连接关闭
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("WebSocket connection closed: {}", ctx.channel().id());
        imService.handleDisconnect(ctx);
    }

    // 处理通道读取事件，接收消息
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        String channelId = ctx.channel().id().asShortText();
        WebSocketSession session = imService.getSessionByChannelId(channelId);
        if (session != null && !session.allowRequest()) {
            log.warn("Rate limit exceeded for session {}, dropping request", channelId);
            return;
        }
        imService.handleMessage(ctx, msg.text());
    }

    // 处理用户事件触发事件，处理空闲超时事件
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            log.info("WebSocket idle timeout: {}", ctx.channel().id());
            imService.handleDisconnect(ctx);
            ctx.close();
        }
    }

    // 处理异常事件，关闭连接
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket error: {}", ctx.channel().id(), cause);
        ctx.close();
    }
}
