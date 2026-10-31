package com.orangecode.tianmu.websocket;

import com.fasterxml.jackson.databind.*;
import com.orangecode.tianmu.application.SessionAuthentication;
import com.orangecode.tianmu.application.VideoLookup;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.constants.WebSocketConstant;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.model.vo.bullet.*;
import com.orangecode.tianmu.producer.RocketMQProducer;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.*;
import io.netty.util.AttributeKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private final RocketMQProducer producer;
    private final SessionAuthentication authentication;
    private final VideoLookup videos;
    private final RoomBatchBroadcaster broadcasts;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final AttributeKey<Long> ROOM = AttributeKey.valueOf(WebSocketHandler.class, "videoId");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        Long room = ctx.channel().attr(ROOM).get();
        if (room == null) { error(ctx, "无效的视频房间"); return; }
        if (frame.content().readableBytes() > 16384) { error(ctx, "弹幕消息过大"); return; }
        JsonNode input;
        try {
            input = JSON.readTree(frame.text());
            if (input == null || !input.isObject()) throw new IllegalArgumentException();
        } catch (Exception malformed) { error(ctx, "弹幕格式错误"); return; }
        try {
            // Browsers cannot set WebSocket Authorization headers. Credentials belong only
            // in the private send frame, never in URLs, broadcasts or persisted events.
            JsonNode credential = input.path("authorization");
            Long userId = authentication.authenticate(credential.isTextual() ? credential.textValue() : null);
            if (userId == null) { reply(ctx, WebSocketConstant.LOGIN_MESSAGE, "登录已失效，请重新登录"); return; }
            if (!matches(input.get("userId"), userId) || !matches(input.get("videoId"), room)) {
                error(ctx, "弹幕身份或视频房间不一致"); return;
            }
            JsonNode content = input.path("content"), position = input.path("playbackTime"), color = input.get("color");
            if (!content.isTextual() || content.textValue().isBlank() || content.textValue().length() > 255
                    || !position.isNumber() || !Double.isFinite(position.doubleValue()) || position.doubleValue() < 0
                    || (color != null && (!color.isTextual() || !color.textValue().matches("#?(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})")))) {
                error(ctx, "请检查弹幕内容、播放时间和颜色"); return;
            }
            videos.requirePublished(room);
            // Explicit projection prevents credentials and client timestamps leaking to MQ.
            var event = new SendBulletRequest();
            event.setUserId(userId); event.setVideoId(room); event.setContent(content.textValue());
            event.setPlaybackTime(position.doubleValue()); event.setColor(color == null ? "#ffffff" : color.textValue());
            event.setBulletId(IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID).nextId());
            event.setCreateTime(new Date());
            producer.sendMessage("tianmu-topic", JSONUtil.toJsonStr(event));
            var output = new OnlineBulletResponse();
            output.setUserId(userId.toString()); output.setBulletId(event.getBulletId().toString());
            output.setPlaybackTime(event.getPlaybackTime()); output.setText(event.getContent());
            broadcasts.append(room, output);
        } catch (BusinessException unavailableVideo) {
            error(ctx, "视频不存在或不可发送弹幕");
        } catch (Exception unavailable) {
            log.warn("Danmaku send failed, cause={}", unavailable.getClass().getSimpleName());
            error(ctx, "弹幕服务暂不可用，请稍后重试");
        }
    }

    private boolean matches(JsonNode supplied, long trusted) {
        return supplied == null || ((supplied.isTextual() || supplied.isIntegralNumber())
                && Long.toString(trusted).equals(supplied.asText()));
    }
    private static String message(int type, Object data) {
        var response = new BulletScreenResponse(); response.setType(type); response.setData(data);
        return JSONUtil.toJsonStr(response);
    }
    private void error(ChannelHandlerContext ctx, String text) { reply(ctx, WebSocketConstant.ERROR_MESSAGE, text); }
    private void reply(ChannelHandlerContext ctx, int type, Object data) {
        ctx.writeAndFlush(new TextWebSocketFrame(message(type, data)));
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            try {
                String path = handshake.requestUri();
                if (!path.matches("/ws/bulletScreen/[1-9][0-9]{0,18}")) throw new IllegalArgumentException();
                long room = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                ctx.channel().attr(ROOM).set(room);
                // Guests may receive public danmaku; each send revalidates a current token.
                broadcasts.join(room, ctx.channel());
            } catch (IllegalArgumentException invalidRoom) {
                ctx.writeAndFlush(new CloseWebSocketFrame(1008, "Invalid video room")).addListener(ChannelFutureListener.CLOSE);
            }
        } else if (event instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            ctx.writeAndFlush(new PingWebSocketFrame());
        } else super.userEventTriggered(ctx, event);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        Long room = ctx.channel().attr(ROOM).getAndSet(null);
        if (room == null) return;
        broadcasts.leave(room, ctx.channel());
    }
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("Danmaku connection failed, cause={}", cause.getClass().getSimpleName());
        ctx.close();
    }
}
