package com.orangecode.tianmu.websocket;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.orangecode.tianmu.application.*;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.producer.RocketMQProducer;
import com.orangecode.tianmu.utils.JwtUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSocketHandlerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private RocketMQProducer producer;
    private VideoLookup videos;
    private SessionAuthentication authentication;
    private ValueOperations<String,String> sessions;
    private String alice, previousKey;
    private RoomBatchBroadcaster broadcasts;

    @BeforeEach void setup() {
        previousKey = System.getProperty("tianmu.jwt.secret-base64");
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        System.setProperty("tianmu.jwt.secret-base64", Base64.getEncoder().encodeToString(key));
        producer = mock(RocketMQProducer.class); videos = mock(VideoLookup.class);
        broadcasts = new RoomBatchBroadcaster(100,1,16384);
        var redis = mock(StringRedisTemplate.class); sessions = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(sessions); authentication = new SessionAuthentication(redis);
        alice = JwtUtil.generate("1"); when(sessions.get("1")).thenReturn(alice);
    }
    @AfterEach void cleanup() {
        for (var channel : channels) channel.finishAndReleaseAll();
        broadcasts.close();
        if (previousKey == null) System.clearProperty("tianmu.jwt.secret-base64");
        else System.setProperty("tianmu.jwt.secret-base64", previousKey);
    }

    @Test void validSendUsesVerifiedIdentityAndRoomWithoutCredentialOrClientTimestampLeak() throws Exception {
        var sender = connect("99"); var viewer = connect("99"); var otherRoom = connect("100");
        var input = message().put("bulletId","7").put("createTime","2000-01-01T00:00:00Z");
        sender.writeInbound(new TextWebSocketFrame(input.toString()));
        var capture = ArgumentCaptor.forClass(String.class);
        verify(producer).sendMessage(eq("tianmu-topic"), capture.capture());
        var queued = JSON.readTree(capture.getValue());
        assertEquals(1,queued.get("userId").asLong()); assertEquals(99,queued.get("videoId").asLong());
        assertNotEquals(7,queued.get("bulletId").asLong()); assertTrue(queued.has("createTime"));
        assertFalse(queued.has("authorization")); assertFalse(capture.getValue().contains(alice));
        var delivered = messages(viewer); assertEquals(1,delivered.size());
        assertEquals(5,delivered.get(0).get("type").asInt());
        assertEquals("1",delivered.get(0).path("data").get(0).path("userId").asText());
        assertFalse(delivered.toString().contains(alice)); assertTrue(messages(otherRoom).isEmpty());
        verify(videos).requirePublished(99L);
    }

    @Test void guestCanReceiveButCannotSendEvenIfVictimHasOnlineSession() throws Exception {
        var sender = connect("99"); var viewer = connect("99");
        sender.writeInbound(new TextWebSocketFrame("{\"userId\":\"1\",\"videoId\":\"99\",\"content\":\"hello\",\"playbackTime\":1}"));
        assertTrue(messages(sender).stream().anyMatch(n -> n.path("type").asInt()==3));
        assertTrue(messages(viewer).isEmpty()); verifyNoInteractions(producer, videos);
    }

    @Test void forgedUserAndCrossRoomMessagesNeverEnqueueOrBroadcast() throws Exception {
        var sender = connect("99"); var viewer = connect("99");
        for (var input : List.of(message().put("userId","2"),message().put("videoId","100")))
            sender.writeInbound(new TextWebSocketFrame(input.toString()));
        assertTrue(messages(sender).stream().allMatch(n -> n.path("type").asInt()==4));
        assertTrue(messages(viewer).isEmpty()); verifyNoInteractions(producer, videos);
    }

    @Test void logoutAndReplacedSessionInvalidateAnAlreadyOpenConnection() throws Exception {
        var sender = connect("99");
        sender.writeInbound(new TextWebSocketFrame(message().toString())); messages(sender);
        reset(producer, videos);
        when(sessions.get("1")).thenReturn(null);
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        when(sessions.get("1")).thenReturn("another-login-token");
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        var results = messages(sender); assertEquals(2,results.size());
        assertTrue(results.stream().allMatch(n -> n.path("type").asInt()==3));
        verifyNoInteractions(producer, videos);
    }

    @Test void expiredAndInvalidTokensCannotSend() throws Exception {
        var sender = connect("99");
        String expired = io.jsonwebtoken.Jwts.builder().setSubject("1").setExpiration(new Date(1))
                .signWith(JwtUtil.getSignInKey(),io.jsonwebtoken.SignatureAlgorithm.HS256).compact();
        for (String token : List.of(expired,"invalid")) sender.writeInbound(new TextWebSocketFrame(message().put("authorization",token).toString()));
        assertTrue(messages(sender).stream().allMatch(n -> n.path("type").asInt()==3));
        verifyNoInteractions(producer, videos);
    }

    @Test void malformedAndInvalidBusinessFieldsAreRejectedWithoutClosingValidConnection() throws Exception {
        var sender = connect("99");
        for (String input : List.of("{", "[]", message().put("content"," ").toString(),
                message().put("content","x".repeat(256)).toString(),message().put("playbackTime",-1).toString(),
                message().put("playbackTime","NaN").toString(),message().put("color","red<script>").toString(),
                message()+" {}", "{\"authorization\":\"a\",\"authorization\":\"b\"}"))
            sender.writeInbound(new TextWebSocketFrame(input));
        var results=messages(sender); assertEquals(9,results.size());
        assertTrue(results.stream().allMatch(n -> n.path("type").asInt()==4));
        assertTrue(sender.isActive()); verifyNoInteractions(producer, videos);
    }

    @Test void oversizedFramesAreRejectedBeforeAuthentication() throws Exception {
        var sender=connect("99");sender.writeInbound(new TextWebSocketFrame("x".repeat(16385)));
        assertEquals(4,messages(sender).get(0).path("type").asInt()); verifyNoInteractions(producer,videos);
    }

    @Test void nonexistentVideoAndQueueFailuresAreNotBroadcastAsSuccess() throws Exception {
        var sender=connect("99");var viewer=connect("99");
        doThrow(new BusinessException(ErrorCode.VIDEO_NOT_FOUND_ERROR)).when(videos).requirePublished(99L);
        sender.writeInbound(new TextWebSocketFrame(message().toString())); verifyNoInteractions(producer);
        reset(videos);doThrow(new IllegalStateException("test queue failure")).when(producer).sendMessage(anyString(),anyString());
        sender.writeInbound(new TextWebSocketFrame(message().toString()));
        assertEquals(2,messages(sender).size()); assertTrue(messages(viewer).isEmpty());
    }

    @Test void invalidRoomPathsAndTokenQueryStringsAreRejected() {
        for(String path:List.of("/ws/bulletScreen/0","/ws/bulletScreen/9999999999999999999",
                "/ws/bulletScreen/99?token=secret","/ws/bulletScreen/99/extra")) {
            var channel=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),new WebSocketHandler(producer,authentication,videos,broadcasts));channels.add(channel);
            channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete(path,new DefaultHttpHeaders(),null));
            assertFalse(channel.isActive());
        }
        verifyNoInteractions(producer,videos);
    }

    private EmbeddedChannel connect(String room) throws Exception {
        var channel=new EmbeddedChannel(io.netty.channel.DefaultChannelId.newInstance(),new WebSocketHandler(producer,authentication,videos,broadcasts));channels.add(channel);
        channel.pipeline().fireUserEventTriggered(new WebSocketServerProtocolHandler.HandshakeComplete("/ws/bulletScreen/"+room,new DefaultHttpHeaders(),null));
        for(var current:channels) messages(current);
        return channel;
    }
    private ObjectNode message() {
        return JSON.createObjectNode().put("authorization",alice).put("videoId","99").put("userId","1")
                .put("content","hello").put("playbackTime",1.25);
    }
    private List<JsonNode> messages(EmbeddedChannel channel) throws Exception {
        channel.runPendingTasks();List<JsonNode> output=new ArrayList<>();Object frame;
        while((frame=channel.readOutbound())!=null) {
            try { if(frame instanceof TextWebSocketFrame text) output.add(JSON.readTree(text.text())); }
            finally { ReferenceCountUtil.release(frame); }
        }
        return output;
    }
}
