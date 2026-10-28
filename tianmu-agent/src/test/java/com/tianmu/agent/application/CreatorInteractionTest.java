package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.interaction.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.video.VideoAnalysisService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"agent.preparation.deterministic-routing=true"})
@AutoConfigureMockMvc
class CreatorInteractionTest {
    @Autowired RunService runs;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CreatorDraftStore drafts;
    @MockBean ModelPort model;
    @MockBean VideoAnalysisService video;
    @MockBean CreatorPublicationPort publications;
    final AccessScope owner = new AccessScope("demo","alice",Set.of(1001L));
    @BeforeEach void script() {
        when(video.analyze(anyLong(),any(),any(),anyString(),any(),any())).thenReturn(new VideoContent.Facts("video-content-v1",1001,"fixture",0,10000,"鸭子在水边",List.of(),"","SAMPLED_FRAMES",List.of(1000L),"fixture",Instant.now()));
        when(model.decide(any(),any())).thenAnswer(invocation -> {
            ModelContext context = invocation.getArgument(0);
            String kind = (String)context.task().specification().conditions().get("kind");
            String skill = kind.equals("COMMENT") ? "smart-comment" : "smart-danmaku";
            if (!context.activeSkills().contains(skill)) return Decision.tool("read_skill",Map.of("name",skill));
            var observed = context.observations().stream().filter(r -> r.tool().equals("analyze_video_content")).findFirst();
            if (observed.isEmpty()) return Decision.tool("analyze_video_content",Map.of("video_id",1001));
            Map<String,Object> item = kind.equals("COMMENT") ? Map.of("text","你们最喜欢哪个片段？") : Map.of("text","这里的鸭子出场啦", "playback_ms",1000);
            return Decision.tool("prepare_creator_interactions",Map.of("video_id",1001,"kind",kind,"video_evidence_id",observed.get().evidenceId(),"items",List.of(item)));
        });
    }
    @Test void bothKindsRunInsideHarnessAndNeverPublishUntilHumanConfirmation() throws Exception {
        for (String kind : List.of("COMMENT","DANMAKU")) {
            String request = json.writeValueAsString(Map.of("message","请为这个视频生成" + (kind.equals("COMMENT") ? "智能评论" : "智能弹幕") + "草稿", "context", Map.of("videoId","1001")));
            var response = mvc.perform(post("/api/agent/runs").header("Authorization","Bearer demo-alice").contentType("application/json").content(request))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            RunState run = runs.owned(UUID.fromString(json.readTree(response).path("runId").asText()),owner);
            long until = System.nanoTime() + 5_000_000_000L;
            while (!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());
            assertEquals("creator.draft",run.snapshot().task().specification().type());
            CreatorDraft draft = drafts.get(run.id);
            assertEquals("DRAFT",draft.state()); assertEquals(kind,draft.kind());
            verifyNoInteractions(publications);
            mvc.perform(get("/api/agent/runs/"+run.id+"/creator-draft").header("Authorization","Bearer demo-bob")).andExpect(status().isNotFound());
            String path = "/api/agent/runs/"+run.id+"/creator-draft/confirm";
            String body = json.writeValueAsString(Map.of("items",draft.items()));
            mvc.perform(post(path).contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            for (int repeat=0; repeat<2; repeat++) mvc.perform(post(path).header("Authorization","Bearer demo-alice").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PUBLISHED"));
            verify(publications,times(1)).publish(any(),any());
            reset(publications);
        }
    }
    @Test void deterministicMeaningDoesNotCollideWithReportsOrMetrics() {
        for (String text : List.of("为视频1001生成智能弹幕草稿", "结合视频1001画面写评论", "分析视频1001后生成弹幕草稿"))
            assertEquals("creator.draft",TaskMeaning.defaults().resolve(text,LocalDate.now()).type());
        assertThrows(IllegalArgumentException.class,()->CreatorInteractionTask.parse("生成智能评论"));
        assertThrows(IllegalArgumentException.class,()->CreatorInteractionTask.parse("视频1001生成评论和弹幕草稿"));
        assertEquals("danmaku.report",TaskMeaning.defaults().resolve("分析视频1001过去七天的弹幕并生成报告，只分析弹幕文字，不要读取视频",LocalDate.now()).type());
    }
    @Test void draftToolAcceptsNativeObjectsButRejectsEncodedJsonExtraKeysAndFractionalTimes() {
        var definition = new com.tianmu.agent.tool.ToolCatalog().all().get("prepare_creator_interactions");
        Map<String,Object> args = new HashMap<>(Map.of("video_id",1001,"video_evidence_id","ev_fixture","kind","DANMAKU",
                "items",List.of(Map.of("text","作者留言","playback_ms",1000))));
        com.tianmu.agent.tool.ToolArguments.validate(new ToolCall("prepare_creator_interactions",args),definition);
        for (Object invalid : List.of("[{\"text\":\"留言\"}]",List.of(Map.of("text","留言","user_id",99)),List.of(Map.of("text","留言","playback_ms",1.5)))) {
            args.put("items",invalid);
            assertThrows(RuntimeException.class,()->com.tianmu.agent.tool.ToolArguments.validate(new ToolCall("prepare_creator_interactions",args),definition));
        }
    }
}
