package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.video.*;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public final class AnalyzeVideoContentTool implements AgentTool {
    private final VideoAnalysisService video;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public AnalyzeVideoContentTool(VideoAnalysisService video, com.fasterxml.jackson.databind.ObjectMapper json) { this.video=video; this.json=json; }
    public String name() { return "analyze_video_content"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        long id=ToolArguments.integer(call,"video_id");
        Long start=call.arguments().containsKey("start_ms")?ToolArguments.integer(call,"start_ms"):null;
        Long end=call.arguments().containsKey("end_ms")?ToolArguments.integer(call,"end_ms"):null;
        context.checkVideoQuery(id,start,end);
        String focus=call.arguments().containsKey("focus")?ToolArguments.text(call,"focus"):"";
        var facts=context.videoQuery(control -> video.analyze(id,start,end,focus,context.scope(),control));
        Evidence evidence=context.registerVideoEvidence(facts);
        Map<String,Object> content=json.convertValue(facts,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        return new ToolResult(name(),Map.of("analysis",content,"evidence_id",evidence.id(),"question",focus,"limitations","Generic sampled-frame facts are reused across questions. Answer the question using these facts; do not infer unseen details. Use a narrower playback interval only when needed. Playback time is distinct from creation time."),evidence.id());
    }
}
