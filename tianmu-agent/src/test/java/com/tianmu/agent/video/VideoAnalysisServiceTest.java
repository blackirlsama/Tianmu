package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.harness.trace.TraceUsage;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoAnalysisServiceTest {
    private static final String VERSION="a".repeat(64);
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static CallControl control() { return new CallControl(Instant.now().plusSeconds(10),new CancellationToken()); }
    private static AccessScope owner(String user) { return new AccessScope("tenant",user,Set.of(7L),"auth"); }
    @SuppressWarnings({"unchecked","rawtypes"})
    private static HttpClient http(String result) {
        HttpClient http=mock(HttpClient.class); HttpResponse<byte[]> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        doReturn(CompletableFuture.completedFuture(response)).when(http).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)); return http;
    }
    private static VideoAnalysisService service(VideoContentPort port, HttpClient http) {
        var beans=new StaticListableBeanFactory(); beans.addBean("video",port);
        return new VideoAnalysisService(new VideoModelProperties(true,"compatible",URI.create("https://vision.example"),"test-key","vision",""),
                beans.getBeanProvider(VideoContentPort.class),new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class),JSON),JSON,OpenTelemetry.noop().getTracer("test"),http);
    }
    private static VideoContentPort port(AtomicInteger authorizations) {
        return new VideoContentPort() {
            public VideoContent.Source source(long id,AccessScope scope,CallControl c) { authorizations.incrementAndGet(); return new VideoContent.Source(id,VERSION,1000); }
            public VideoContent.Media media(long id,String v,long start,long end,AccessScope scope,CallControl c) {
                return new VideoContent.Media(new VideoContent.Source(id,v,1000),start,end,List.of(new VideoContent.Frame(500,"/9j/")),"");
            }
        };
    }
    @Test void contentIsAnchoredCachedPerOwnerAndUsageCountedWithoutInventingAudio() {
        String response="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"summary\\\":\\\"角色进入画面\\\",\\\"moments\\\":[{\\\"atMs\\\":500,\\\"observation\\\":\\\"角色出现在画面左侧\\\"}]}\"}}],\"usage\":{\"prompt_tokens\":17,\"completion_tokens\":9}}";
        var auth=new AtomicInteger(); var http=http(response); var service=service(port(auth),http); var usage=new TraceUsage();
        try (var ignored=Context.current().with(TraceUsage.KEY,usage).makeCurrent()) {
            var first=service.analyze(7,null,null,"",owner("1"),control());
            assertEquals("SAMPLED_FRAMES",first.coverage()); assertEquals("",first.transcript());
            assertEquals(500,first.moments().get(0).atMs());
            assertEquals(first,service.analyze(7,null,null,"",owner("1"),control()));
            assertEquals(2,auth.get(),"Reauthorize even on cache hits");
            assertEquals(1L,usage.totals().get("usage.reported_calls"));
            assertEquals(26L,usage.totals().get("usage.total_tokens"));
            service.analyze(7,null,null,"",owner("2"),control());
            assertEquals(2L,usage.totals().get("usage.reported_calls"),"Cache must not cross owners");
        }
    }
    @Test void mismatchedSourceAndHalfRangesCannotProduceEvidence() {
        var http=http("{}"); var service=service(port(new AtomicInteger()),http);
        assertEquals("INVALID_ARGUMENTS",assertThrows(AgentException.class,()->service.analyze(7,0L,null,"",owner("1"),control())).code());
        assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->service.analyze(8,null,null,"",owner("1"),control())).code());
        verifyNoInteractions(http);
    }
    @Test void fabricatedTimeAnchorsAreRejected() {
        var http=http("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"summary\\\":\\\"内容\\\",\\\"moments\\\":[{\\\"atMs\\\":999,\\\"observation\\\":\\\"未提供的位置\\\"}]}\"}}]}");
        var service=service(port(new AtomicInteger()),http);
        assertEquals("INVALID_VIDEO_ANALYSIS",assertThrows(AgentException.class,()->service.analyze(7,null,null,"",owner("1"),control())).code());
    }
    @Test void standaloneVideoTaskDoesNotRequireDanmakuDate() {
        var meaning=com.tianmu.agent.preparation.TaskMeaning.defaults();
        assertEquals("video.content",meaning.resolve("请分析视频7的画面内容",LocalDate.now()).type());
        assertEquals(Map.of("video_id",7L),meaning.resolve("请分析视频7的画面内容",LocalDate.now()).conditions());
    }
    @Test void miniMaxUsesItsVlmEndpointAndOneTimestampedSheet() throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,32,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpeg",bytes);
        String image=Base64.getEncoder().encodeToString(bytes.toByteArray());
        VideoContentPort port=new VideoContentPort() {
            public VideoContent.Source source(long id,AccessScope scope,CallControl c) { return new VideoContent.Source(id,VERSION,1000); }
            public VideoContent.Media media(long id,String v,long start,long end,AccessScope scope,CallControl c) {
                return new VideoContent.Media(new VideoContent.Source(id,v,1000),start,end,List.of(new VideoContent.Frame(500,image)),"");
            }
        };
        var beans=new StaticListableBeanFactory(); beans.addBean("video",port);
        HttpClient http=http("{\"base_resp\":{\"status_code\":0},\"content\":\"```json\\n{\\\"summary\\\":\\\"黑色画面\\\",\\\"moments\\\":[{\\\"atMs\\\":500,\\\"observation\\\":\\\"未见明显内容\\\"}]}\\n```\"}");
        var service=new VideoAnalysisService(new VideoModelProperties(true,"minimax",URI.create("https://api.minimaxi.com"),"test-key","minimax-vlm-service",""),beans.getBeanProvider(VideoContentPort.class),
                new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class),JSON),JSON,OpenTelemetry.noop().getTracer("test"),http);
        assertEquals("黑色画面",service.analyze(7,null,null,"",owner("1"),control()).summary());
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).sendAsync(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("/v1/coding_plan/vlm",request.getValue().uri().getPath());
    }
}
