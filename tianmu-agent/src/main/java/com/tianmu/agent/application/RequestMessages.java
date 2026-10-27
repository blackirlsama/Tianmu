package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import java.util.regex.Pattern;

/** Separates the user's words from the page target, including compatibility with older clients. */
public final class RequestMessages {
    private RequestMessages() {}
    private static final Pattern LEGACY = Pattern.compile("\\n\\n\\[页面上下文：当前视频([1-9][0-9]{0,18})。\\]$");
    public record Input(String message, RequestContext context) {}
    public static Input normalize(String message, RequestContext context) {
        String raw = message.strip();
        var legacy = LEGACY.matcher(raw);
        if (legacy.find()) {
            var previous = new RequestContext(legacy.group(1));
            if (context != null && !context.equals(previous)) throw new AgentException("INVALID_REQUEST", "Conflicting page context");
            context = previous; raw = raw.substring(0, legacy.start()).strip();
        }
        if (raw.isBlank()) throw new AgentException("INVALID_REQUEST", "Message is required");
        return new Input(raw, context);
    }
    public static String execution(String message, RequestContext context) {
        // Only data questions need a page target. Explicit user targets always take precedence.
        if (context == null || !(message.contains("弹幕") || com.tianmu.agent.preparation.AudienceReportTask.requested(message) || com.tianmu.agent.preparation.CommentAnalysisTask.requested(message) || ConversationTopics.videoContent(message))
                || ConversationTopics.general(message) || DanmakuTarget.matcher(message).find()) return message;
        return message + "\n当前视频：视频" + context.videoId();
    }
}
