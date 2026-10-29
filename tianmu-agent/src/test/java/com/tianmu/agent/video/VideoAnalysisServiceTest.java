package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

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
        var budget = new ModelBudget(100, 50);
        try (var ignored=Context.current().with(TraceUsage.KEY,usage).makeCurrent()) {
            var first=service.analyze(7,null,null,"",owner("1"),control().withBudget(budget));
            assertEquals("SAMPLED_FRAMES",first.coverage()); assertEquals("",first.transcript());
            assertEquals(500,first.moments().get(0).atMs());
            assertEquals(first,service.analyze(7,null,null,"",owner("1"),control().withBudget(budget)));
            assertEquals(3,auth.get(),"Reauthorize shared results and cache hits");
            assertEquals(1L,usage.totals().get("usage.reported_calls"));
            assertEquals(26L,usage.totals().get("usage.total_tokens"));
            service.analyze(7,null,null,"",owner("2"),control().withBudget(budget));
            assertEquals(2L,usage.totals().get("usage.reported_calls"),"Cache must not cross owners");
            assertEquals(52L, budget.attributes().get("budget.charged_tokens"), "Cache hit must not reserve or charge tokens");
        }
    }
    @Test void tokenBudgetRejectsBeforeVisionNetworkRequest() {
        var http = http("{}");
        var service = service(port(new AtomicInteger()), http);
        var budget = new ModelBudget(10, 50);
        try (var scope = Context.current().makeCurrent()) {
            assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED", assertThrows(AgentException.class, () -> service.analyze(7, null, null, "", owner("1"), control().withBudget(budget))).code());
            verifyNoInteractions(http);
        }
    }
    @Test void differentlyWordedQuestionsReuseGenericFactsAndStillReauthorize() {
        var beans=new StaticListableBeanFactory();var authorizations=new AtomicInteger();beans.addBean("video",port(authorizations));
        var vision=mock(VisionModelPort.class);
        when(vision.understand(any(),eq(VideoAnalysisService.FACT_FOCUS),anyString(),any())).thenReturn(JSON.createObjectNode().put("summary","角色在草地上").set("moments",JSON.createArrayNode().add(JSON.createObjectNode().put("atMs",500).put("observation","草地上的角色"))));
        var service=new VideoAnalysisService(new VideoModelProperties(true,"compatible",URI.create("https://vision.example"),"key","vision",""),beans.getBeanProvider(VideoContentPort.class),new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class),JSON),OpenTelemetry.noop().getTracer("test"),vision,mock(TranscriptionPort.class),new VideoAnalysisFlights());
        var first=service.analyze(7,null,null,"下一条视频给我灵感",owner("1"),control());
        assertEquals(first,service.analyze(7,null,null,"就是现在的视频，换个措辞问灵感",owner("1"),control()));
        verify(vision,times(1)).understand(any(),eq(VideoAnalysisService.FACT_FOCUS),anyString(),any());
        assertEquals(3,authorizations.get());
        assertEquals("FORBIDDEN",assertThrows(AgentException.class,()->service.analyze(7,null,null,"同一个问题",new AccessScope("tenant","1",Set.of()),control())).code());
        service.analyze(7,null,null,"另一个作者",owner("2"),control());
        service.analyze(7,0L,700L,"新片段",owner("1"),control());
        verify(vision,times(3)).understand(any(),eq(VideoAnalysisService.FACT_FOCUS),anyString(),any());
    }
    @Test void transcriptionWithoutUsageRetainsBudgetAndPreventsVisionDispatch() {
        VideoContentPort port = new VideoContentPort() {
            public VideoContent.Source source(long id, AccessScope scope, CallControl c) { return new VideoContent.Source(id, VERSION, 1000); }
            public VideoContent.Media media(long id, String version, long start, long end, AccessScope scope, CallControl c) {
                return new VideoContent.Media(source(id, scope, c), start, end,
                        List.of(new VideoContent.Frame(500, "/9j/")), Base64.getEncoder().encodeToString(new byte[]{1, 2}));
            }
        };
        var beans = new StaticListableBeanFactory(); beans.addBean("video", port);
        var http = http("{\"text\":\"audio transcript\"}");
        var service = new VideoAnalysisService(new VideoModelProperties(true, "compatible", URI.create("https://vision.example"), "key", "vision", "asr"),
                beans.getBeanProvider(VideoContentPort.class), new VideoContentCache(beans.getBeanProvider(javax.sql.DataSource.class), JSON), JSON,
                OpenTelemetry.noop().getTracer("test"), http);
        var budget = new ModelBudget(50, 50);
        try (var scope = Context.current().makeCurrent()) {
            assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED", assertThrows(AgentException.class,
                    () -> service.analyze(7, null, null, "", owner("1"), control().withBudget(budget))).code());
            assertEquals(1L, budget.attributes().get("budget.unsettled_calls"));
            var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
            verify(http, times(1)).sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals("/audio/transcriptions", request.getValue().uri().getPath());
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
