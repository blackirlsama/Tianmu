package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;

@Component
public final class ReadHistoryTool implements AgentTool {
    public String name() { return "read_history"; }
    public Exposure exposure() { return Exposure.GENERAL; }
    public int order() { return 35; }
    public ToolResult execute(ToolCall call,ToolContext context) {
        long before=call.arguments().containsKey("before") ? ToolArguments.integer(call,"before") : Long.MAX_VALUE;
        int limit=call.arguments().containsKey("limit") ? Math.toIntExact(ToolArguments.integer(call,"limit")) : 4;
        String query=call.arguments().containsKey("query") ? ToolArguments.text(call,"query") : "";
        return new ToolResult(name(),context.history(before,limit,query),null);
    }
}
