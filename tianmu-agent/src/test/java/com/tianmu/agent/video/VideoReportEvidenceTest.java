package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.tool.ToolContext;
import com.tianmu.agent.tool.builtin.CreateDanmakuReportTool;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoReportEvidenceTest {
    @Test void reportEscapesVideoTextAndCarriesBothOwnedEvidenceIds() {
        var context=mock(ToolContext.class); var run=UUID.randomUUID();
        var owner=new AccessScope("t","1",Set.of(7L),"auth");
        var query=new Query(7,LocalDate.now().minusDays(1).atStartOfDay(),LocalDate.now().atStartOfDay());
        var dataset=new Dataset(query,0,0,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,0)).toList(),List.of(),Instant.now(),"mysql:bullet:danmaku-retained-v1",false);
        when(context.evidence("danmaku")).thenReturn(new Evidence("danmaku",run,owner,null,dataset));
        var facts=new VideoContent.Facts("video-content-v1",7,"a".repeat(64),0,1000,"<script>bad</script>",List.of(new VideoContent.Moment(500,"画面")),"","SAMPLED_FRAMES",List.of(500L),"minimax-vlm-service",Instant.now());
        when(context.evidence("video")).thenReturn(new Evidence("video",run,owner,null,null,facts));
        when(context.saveReport(eq("danmaku"),any())).thenReturn(new ReportRef("report","/report"));
        var result=new CreateDanmakuReportTool(new ObjectMapper().findAndRegisterModules()).execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","danmaku","sections_json","[]","video_evidence_id","video")),context);
        var html=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(context).saveReport(eq("danmaku"),html.capture());
        String document=new String(html.getValue(),StandardCharsets.UTF_8);
        assertTrue(document.contains("视频内容证据")); assertTrue(document.contains("00:00.500"));
        assertTrue(document.contains("&lt;script&gt;")); assertFalse(document.contains("<script>bad"));
        assertEquals(List.of("danmaku","video"),result.terminalAnswer().evidenceIds());
    }
}
