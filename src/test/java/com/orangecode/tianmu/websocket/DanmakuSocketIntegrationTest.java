package com.orangecode.tianmu.websocket;

import com.fasterxml.jackson.databind.*;
import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.producer.RocketMQProducer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DanmakuSocketIntegrationTest {
    /** Real HTTP upgrade and WebSocket frames through the production Netty pipeline. */
    @Test void anonymousReceiveAuthenticatedSendAndRevocationWorkOverActualSocket() throws Exception {
        var producer = mock(RocketMQProducer.class);
        var authentication = mock(SessionAuthentication.class);
        when(authentication.authenticate(isNull())).thenReturn(null);
        when(authentication.authenticate("valid-session")).thenReturn(1L);
        var videos = mock(VideoLookup.class);
        var broadcasts = new RoomBatchBroadcaster(100,50,16384);
        var boss = new NioEventLoopGroup(1); var workers = new NioEventLoopGroup(1);
        var messages = new io.netty.util.concurrent.DefaultEventExecutorGroup(1,
                new io.netty.util.concurrent.DefaultThreadFactory("test-danmaku-business"),
                32, io.netty.util.concurrent.RejectedExecutionHandlers.reject());
        Channel server = null; WebSocket socket = null;
        try {
            server = new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override protected void initChannel(SocketChannel channel) {
                            NettyService.configurePipeline(channel.pipeline(),producer,authentication,videos,broadcasts,messages);
                        }
                    }).bind("127.0.0.1",0).sync().channel();
            var listener = new Messages();
            int port = ((InetSocketAddress)server.localAddress()).getPort();
            socket = HttpClient.newHttpClient().newWebSocketBuilder()
                    .buildAsync(URI.create("ws://127.0.0.1:"+port+"/ws/bulletScreen/99"),listener).get(10,TimeUnit.SECONDS);
            assertEquals(1,listener.next().path("type").asInt());
            socket.sendText("{\"userId\":\"1\",\"content\":\"guest\",\"playbackTime\":0}",true).get(5,TimeUnit.SECONDS);
            assertEquals(3,listener.next().path("type").asInt()); verifyNoInteractions(producer);
            socket.sendText("{\"authorization\":\"valid-session\",\"content\":\"hello\",\"playbackTime\":0}",true).get(5,TimeUnit.SECONDS);
            var delivered=listener.next();assertEquals(5,delivered.path("type").asInt());
            assertEquals("1",delivered.path("data").get(0).path("userId").asText());
            when(authentication.authenticate("valid-session")).thenReturn(null);
            socket.sendText("{\"authorization\":\"valid-session\",\"content\":\"after logout\",\"playbackTime\":0}",true).get(5,TimeUnit.SECONDS);
            assertEquals(3,listener.next().path("type").asInt());
            verify(producer,times(1)).sendMessage(eq("tianmu-topic"),anyString());
            socket.sendClose(WebSocket.NORMAL_CLOSURE,"done").get(5,TimeUnit.SECONDS);
        } finally {
            if(socket!=null)socket.abort();
            broadcasts.close();
            if(server!=null)server.close().syncUninterruptibly();
            messages.shutdownGracefully(1,5,TimeUnit.SECONDS).syncUninterruptibly();
            workers.shutdownGracefully(1,5,TimeUnit.SECONDS).syncUninterruptibly();
            boss.shutdownGracefully(0,3,TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static final class Messages implements WebSocket.Listener {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder current = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            current.append(text);
            if(last){messages.add(current.toString());current.setLength(0);}
            socket.request(1);return CompletableFuture.completedFuture(null);
        }
        JsonNode next() throws Exception {
            String value=messages.poll(10,TimeUnit.SECONDS);assertNotNull(value,"WebSocket response timeout");
            return new ObjectMapper().readTree(value);
        }
    }
}
