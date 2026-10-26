package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import java.util.*;

/** Coverage notices are derived from admitted evidence, never supplied by the answering model. */
public final class EvidencePresentation {
    private EvidencePresentation() {}
    public static void requireKind(TaskTypes.Specification specification, List<Evidence> evidence) {
        boolean supported = switch (specification.type()) {
            case "video.metric" -> evidence.stream().anyMatch(e -> e.data() != null);
            case "video.content" -> evidence.stream().anyMatch(e -> e.videoContent() != null);
            case "danmaku.report", "danmaku.overview" -> evidence.stream().anyMatch(e -> e.analysis() != null);
            default -> true;
        };
        if (!supported) throw new AgentException("INVALID_EVIDENCE", "Answer lacks evidence required by the prepared task");
    }
    public static List<AnswerNote> notes(List<Evidence> evidence) {
        List<AnswerNote> notes = new ArrayList<>();
        for (Evidence value : evidence) {
            var data = value.data();
            if (data != null && "mysql:bullet:retained-persisted-v1".equals(data.source()))
                notes.add(new AnswerNote("视频" + data.videoId() + " · " + data.date(),
                        "按创建日期统计当前保留的已入库弹幕；不含已删除记录，也不代表全部已发送弹幕。", data.dataAsOf(), data.timezone()));
            var video = value.videoContent();
            if (video != null) {
                String audio = video.transcript().isBlank() ? "未提供可用音频转写，不能据此判断对白或音效。"
                        : "音频转写仅覆盖该区间，未提供逐句时间定位。";
                notes.add(new AnswerNote("视频" + video.videoId() + " · 播放区间" + video.startMs() + "–" + video.endMs() + "ms",
                        "来源版本：" + video.sourceVersion() + "；仅采样" + video.sampledAtMs().size() + "帧，位置：" + video.sampledAtMs()
                                + "ms；可能遗漏短暂事件，不能代表逐帧或连续动作分析。" + audio, video.analyzedAt(), "Asia/Shanghai"));
            }
        }
        return List.copyOf(notes);
    }
}
