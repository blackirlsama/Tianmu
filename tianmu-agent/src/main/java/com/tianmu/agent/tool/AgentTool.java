package com.tianmu.agent.tool;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.skill.SkillCatalog;

/** One trusted Java handler per tool. Descriptions and input schemas live in resources/tools. */
public interface AgentTool {
    enum Exposure { GENERAL, SKILL }
    String name();
    default int order() { return 100; }
    /** Business tools are hidden by default. Exposure never grants access to business data. */
    default Exposure exposure() { return Exposure.SKILL; }
    default boolean canActivateSkills() { return false; }
    /** Independent reads may share one model turn. Workflow mutations remain strictly single-call. */
    default boolean batchable() { return false; }
    default ToolDefinition describe(ToolDefinition declared, SkillCatalog skills) { return declared; }
    ToolResult execute(ToolCall call, ToolContext context);
}
