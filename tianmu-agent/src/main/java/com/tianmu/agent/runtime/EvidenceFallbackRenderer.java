package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import java.util.*;

/** Deliberately narrow fallback: only complete metric evidence; never arbitrary tool JSON. */
public final class EvidenceFallbackRenderer {
    private EvidenceFallbackRenderer() {}
    public static Answer render(RunState run) {
        List<Evidence> evidence = run.execution().evidence().snapshot().values().stream().filter(item -> item.data() != null && item.runId().equals(run.id)
                && item.owner().equals(run.owner) && run.owner.videoIds().contains(item.data().videoId())
                && item.data().complete() && item.data().value() >= 0 && item.data().metric().equals("bullet_count")
                && item.data().timezone().equals("Asia/Shanghai"))
                .sorted(Comparator.comparing((Evidence item) -> item.data().date()).thenComparing(item -> item.data().videoId()))
                .limit(10).toList();
        if (evidence.isEmpty()) throw new AgentException("STEP_LIMIT", "No usable evidence for finalization");
        StringJoiner text = new StringJoiner("\n");
        if (evidence.stream().anyMatch(item -> item.data().demo())) text.add("【模拟数据】");
        for (Evidence item : evidence) {
            String day = item.data().date().format(java.time.format.DateTimeFormatter.ofPattern(
                    item.data().date().getYear() == run.businessDate.getYear() ? "M月d日" : "yyyy年M月d日"));
            String target = evidence.size() == 1 ? "这个视频" : "视频" + item.data().videoId();
            text.add("查到了，" + target + "在" + day + "共有"
                    + String.format(Locale.ROOT, "%,d", item.data().value()) + "条弹幕。");
        }
        return new Answer(text.toString(), evidence.stream().map(Evidence::id).toList());
    }
}
