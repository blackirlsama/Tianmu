package com.tianmu.agent.preparation;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Supported playback grammar is deterministic; unsupported or conflicting ranges require clarification. */
public final class VideoContentTask {
    private VideoContentTask() {}
    private static final String JOIN = "\\s*(?:到|至|[-~～—])\\s*";
    private static final Pattern SECONDS = Pattern.compile("(?<![\\d.])第?([+-]?\\d+(?:\\.\\d+)?)\\s*秒?" + JOIN + "第?([+-]?\\d+(?:\\.\\d+)?)\\s*秒");
    private static final Pattern CLOCK = Pattern.compile("(?<![\\d:])(\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?)" + JOIN + "(\\d{1,3}:[0-5]\\d(?:\\.\\d{1,3})?)(?![\\d:])");
    public static boolean requested(String text) {
        if (CreatorInteractionTask.requested(text)) return false;
        return !AudienceReportTask.requested(text) && !DanmakuReportTask.requested(text) && !CommentAnalysisTask.requested(text) && !text.contains("弹幕") && !ConversationTopics.general(text)
                && text.matches("(?s).*(内容|画面|剧情|镜头|对白|讲了什么|发生了什么).*");
    }
    public static Specification parse(String text) {
        var ids = DanmakuTarget.matcher(text).results().toList();
        if (ids.size() != 1) throw new IllegalArgumentException("请明确一个要分析的视频ID。");
        Map<String, Object> values = new LinkedHashMap<>();
        try { values.put("video_id", Long.parseLong(ids.get(0).group(1))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("请提供有效的视频ID。"); }
        var seconds = SECONDS.matcher(text).results().toList();
        var clocks = CLOCK.matcher(text).results().toList();
        if (seconds.size() + clocks.size() > 1) throw new IllegalArgumentException("请一次指定一个播放区间。");
        String remaining = text;
        if (!seconds.isEmpty()) {
            var match = seconds.get(0);
            values.put("start_ms", millis(match.group(1))); values.put("end_ms", millis(match.group(2)));
            remaining = text.substring(0, match.start()) + text.substring(match.end());
        } else if (!clocks.isEmpty()) {
            var match = clocks.get(0);
            values.put("start_ms", clock(match.group(1))); values.put("end_ms", clock(match.group(2)));
            remaining = text.substring(0, match.start()) + text.substring(match.end());
        }
        if (Pattern.compile("秒|分钟|\\d\\s*分|\\d\\s*[:：]|时段|区间|时间范围").matcher(remaining).find())
            throw new IllegalArgumentException("请用完整播放区间，例如第2秒到第6秒，或00:02到00:06（最多120秒）。");
        Map<String, String> provenance = new LinkedHashMap<>();
        values.keySet().forEach(key -> provenance.put(key, "user_input"));
        try { return new Specification("video.content", values, provenance); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("播放区间须从非负时间开始，结束晚于开始，且不超过120秒。"); }
    }
    private static long millis(String value) {
        try { return new BigDecimal(value).movePointRight(3).longValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("播放时间须为毫秒精度的有效数字。"); }
    }
    private static long clock(String value) {
        String[] parts = value.split(":");
        return Math.addExact(Math.multiplyExact(Long.parseLong(parts[0]), 60_000), millis(parts[1]));
    }
}
