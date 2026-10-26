package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.query.DanmakuQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.profiles.active=demo","harness.trace.directory=./target/full-report-api-traces"})
@AutoConfigureMockMvc
@Import(FullReportApiTest.Fixtures.class)
class FullReportApiTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;
    @Test void skillAndThreeToolsCompleteFullReportBeyondTheOrdinarySixTurnLimit() throws Exception {
        byte[] body=http.perform(post("/api/agent/runs").header("Authorization","Bearer demo-alice").contentType("application/json")
                .content("{\"message\":\"分析视频1001过去七天的弹幕并生成报告，仅分析弹幕文字，不要读取视频\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray();
        String id=json.readTree(body).path("runId").asText();JsonNode snapshot=null;long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        do{
            snapshot=json.readTree(http.perform(get("/api/agent/runs/"+id).header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            if(!Set.of("QUEUED","RUNNING").contains(snapshot.path("status").asText()))break;Thread.sleep(20);
        }while(System.nanoTime()<until);
        assertEquals("SUCCEEDED",snapshot.path("status").asText(),snapshot.toString());assertEquals(7,snapshot.path("steps").asInt());
        String text=snapshot.path("answer").path("text").asText();assertTrue(text.contains("全部 360 条"));
        var match=java.util.regex.Pattern.compile("/api/agent/runs/[a-z0-9-]+/reports/rp_[a-z0-9-]+").matcher(text);assertTrue(match.find());
        String link=match.group();http.perform(get(link)).andExpect(status().isUnauthorized());
        String html=http.perform(get(link).header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(html.contains("360 条相关弹幕"));assertTrue(html.contains("先看结论"));assertFalse(html.contains("7000000000000000000"));
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary DanmakuQueryPort fullData(){return (query,scope,control)->{
            Dataset d=new ReportSkillFlowTest().data(360);return new Dataset(query,d.totalMessages(),d.uniqueSenders(),d.buckets(),d.samples(),d.dataAsOf(),d.source(),d.demo(),d.audience());
        };}
        @Bean @Primary ModelPort reportModel(ObjectMapper json){return context->{
            if(!context.activeSkills().contains("danmaku-analysis"))return Decision.tool("read_skill",Map.of("name","danmaku-analysis"));
            if(context.observations().stream().noneMatch(r->r.tool().equals("query_danmaku"))){
                Query q=((TaskConditions.Report)context.task().specification().typed()).query();
                return Decision.tool("query_danmaku",Map.of("video_id",q.videoId(),"start",q.start().toString(),"end",q.end().toString()));
            }
            ToolResult latest=context.observations().get(context.observations().size()-1);
            @SuppressWarnings("unchecked") Map<String,Object> progress=(Map<String,Object>)latest.data().get("classification");
            @SuppressWarnings("unchecked") List<Map<String,Object>> groups=(List<Map<String,Object>>)progress.get("groups");
            try{
                String topics=json.writeValueAsString(List.of(Map.of("topic_key","T1","title","观众喜欢小鸭的绒毛","observation","观众喜欢小鸭，希望继续看到日常观察片段。","recommendation","保持自然近景并更新成长日常。","sample_ids",groups.stream().map(g->g.get("id")).toList())));
                return Decision.tool("create_danmaku_report",Map.of("evidence_id",latest.evidenceId(),"offset",progress.get("offset"),"topics_json",topics));
            }catch(Exception e){throw new IllegalStateException(e);}
        };}
    }
}
