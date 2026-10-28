package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.*;
import com.tianmu.agent.query.VideoPagePort;
import com.tianmu.agent.model.ModelPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="harness.trace.directory=./target/test-traces/context-conversation")
@AutoConfigureMockMvc
class ContextConversationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RunService runs;
    @MockBean ModelPort model;
    @MockBean VideoPagePort pages;
    final AccessScope owner=new AccessScope("demo","alice",Set.of(1001L));
    Snapshot send(String words,UUID session) throws Exception {
        var body=new HashMap<String,Object>();body.put("message",words);body.put("context",Map.of("videoId","1001","videoTitle","untrusted client title"));
        if(session!=null)body.put("sessionId",session);
        var raw=mvc.perform(post("/api/agent/runs").header("Authorization","Bearer demo-alice").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(raw).path("runId").asText());
        var run=runs.owned(id,owner);long until=System.nanoTime()+5_000_000_000L;
        while(!AgentTypes.terminal(run.snapshot().status()) && System.nanoTime()<until)Thread.sleep(10);
        assertEquals(Status.SUCCEEDED,run.snapshot().status(),run.snapshot().errorCode());return run.snapshot();
    }
    @Test void currentVideoAndFollowupGoalReachHarnessWithoutPreparationModelOrSyntheticUserText() throws Exception {
        when(pages.page(eq(1001L),any())).thenReturn(new VideoPagePort.Page("1001","动物 · 鸭子",32000));
        var seen=new ArrayList<ModelContext>();
        when(model.decide(any(),any())).thenAnswer(call->{
            ModelContext c=call.getArgument(0);seen.add(c);
            assertEquals("动物 · 鸭子",c.memory().environment().videoTitle());
            assertEquals("1001",c.memory().environment().videoId());
            assertFalse(c.message().contains("当前视频："));
            if(c.message().equals("就是现在的视频")) {
                assertEquals("下一个视频给我一点灵感",c.memory().focus().request());
                assertTrue(c.memory().recent().stream().anyMatch(m->m.role().equals("user") && m.text().equals("下一个视频给我一点灵感")));
            }
            return Decision.answer("可以参考当前鸭子视频，进一步探索下一条视频的拍摄方向。",List.of());
        });
        var first=send("下一个视频给我一点灵感",null);
        var second=send("就是现在的视频",first.sessionId());
        assertEquals(0,first.preparationCalls());assertEquals(0,second.preparationCalls());
        assertEquals(2,seen.size());assertEquals("conversation",second.task().specification().type());
    }
    @Test void focusSwitchesTopicsAndDoesNotCarryAnOldVideoGoalIntoAnotherPage() {
        UUID first=UUID.randomUUID(),next=UUID.randomUUID();
        var old=ConversationFocus.update(first,"下一个视频给我一点灵感",new RequestContext("1001"),null);
        assertEquals(old,ConversationFocus.update(next,"就是现在的视频",new RequestContext("1001"),old));
        var changed=ConversationFocus.update(next,"就是现在的视频",new RequestContext("1002"),old);
        assertEquals("1002",changed.referenceVideoId());assertEquals("就是现在的视频",changed.request());
        assertEquals("换个话题，帮我写介绍",ConversationFocus.update(next,"换个话题，帮我写介绍",new RequestContext("1001"),old).request());
    }
    @Test void historyPagingDoesNotSkipMessagesOutsideTheReturnedLimit() throws Exception {
        when(pages.page(eq(1001L),any())).thenReturn(new VideoPagePort.Page("1001","动物 · 鸭子",32000));
        var sequences=new ArrayList<Long>();
        when(model.decide(any(),any())).thenAnswer(call->{
            ModelContext c=call.getArgument(0);
            if(c.message().equals("分页查看之前的对话")) {
                var results=c.observations().stream().filter(r->r.tool().equals("read_history")).toList();
                if(!results.isEmpty()) {
                    var last=results.get(results.size()-1);
                    for(var raw:(List<?>)last.data().get("messages"))sequences.add(((Number)((Map<?,?>)raw).get("sequence")).longValue());
                    if(!Boolean.TRUE.equals(last.data().get("more_available")))return Decision.answer("已找回历史。",List.of());
                    return Decision.tool("read_history",Map.of("limit",3,"before",last.data().get("next_before")));
                }
                return Decision.tool("read_history",Map.of("limit",3));
            }
            return Decision.answer("收到。",List.of());
        });
        var first=send("先聊构图",null);
        send("再聊光线",first.sessionId());send("再聊角度",first.sessionId());send("再聊文案",first.sessionId());
        send("分页查看之前的对话",first.sessionId());
        assertEquals(java.util.stream.LongStream.rangeClosed(1,8).boxed().toList(),sequences.stream().sorted().toList());
    }
    @Test void olderHistoryCanBeReadOnDemandThroughTheGeneralTool() throws Exception {
        when(pages.page(eq(1001L),any())).thenReturn(new VideoPagePort.Page("1001","动物 · 鸭子",32000));
        when(model.decide(any(),any())).thenAnswer(call->{
            ModelContext c=call.getArgument(0);
            if(c.message().equals("找回更早的灵感")) {
                assertFalse(c.memory().recent().stream().anyMatch(m->m.text().equals("最初的灵感问题")));
                var history=c.observations().stream().filter(r->r.tool().equals("read_history")).findFirst();
                if(history.isEmpty())return Decision.tool("read_history",Map.of("query","最初的灵感","limit",4));
                var messages=(List<?>)history.get().data().get("messages");
                assertTrue(messages.stream().anyMatch(raw->((Map<?,?>)raw).get("text").equals("最初的灵感问题")));
            }
            return Decision.answer("已记录你的问题。",List.of());
        });
        var first=send("最初的灵感问题",null);
        send("聊聊拍摄角度",first.sessionId());send("再聊光线",first.sessionId());
        var recalled=send("找回更早的灵感",first.sessionId());
        assertEquals(2,recalled.modelCalls());assertEquals(0,recalled.preparationCalls());
    }
}
