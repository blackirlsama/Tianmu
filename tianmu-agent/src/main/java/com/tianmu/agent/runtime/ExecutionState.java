package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentTypes.ToolResult;
import com.tianmu.agent.runtime.store.*;
import java.util.*;

/** Host-owned mutable execution data. Tools receive a restricted facade instead. */
public final class ExecutionState {
    private final Set<String> activeSkills = new HashSet<>();
    private final List<ToolResult> observations = new ArrayList<>();
    private final EvidenceStore evidence;
    private final Map<String, Integer> commentOffsets = new HashMap<>();
    public synchronized int commentOffset(String id) { return commentOffsets.getOrDefault(id, 0); }
    public synchronized void advanceComments(String id, int expected, int next) {
        if (commentOffset(id) != expected || next < expected) throw new com.tianmu.agent.core.AgentException("INVALID_COMMENT_OFFSET", "Read comment pages in order");
        commentOffsets.put(id, next);
    }
    private final Map<String,com.tianmu.agent.core.DanmakuClassification> classifications=new HashMap<>();
    public synchronized boolean hasClassification(String id){return classifications.containsKey(id);}
    public synchronized com.tianmu.agent.core.DanmakuClassification classification(String id){return classifications.getOrDefault(id,com.tianmu.agent.core.DanmakuClassification.empty());}
    public synchronized void classification(String id,com.tianmu.agent.core.DanmakuClassification progress){classifications.put(id,progress);}
    public ExecutionState() { this(new InMemoryEvidenceStore()); }
    public ExecutionState(EvidenceStore evidence) { this.evidence = evidence; }
    public synchronized Set<String> activeSkills() { return Set.copyOf(activeSkills); }
    public synchronized void activateSkill(String name) { activeSkills.add(name); }
    public synchronized List<ToolResult> observations() { return List.copyOf(observations); }
    public synchronized void observe(ToolResult value) { observations.add(value); }
    public EvidenceStore evidence() { return evidence; }
}
