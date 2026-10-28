package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.MemoryTypes.Focus;
import java.util.*;

/** Retains the user's own request, never an LLM-generated assertion or permission grant. */
public final class ConversationFocus {
    private ConversationFocus() {}
    private static boolean continuation(String text) {
        return text.length() <= 80 && text.matches("(?s)(?:就是|就|对[，,]?|是[的，,]?|继续|再|更|按|基于|针对).*");
    }
    /** Existing sessions can recover a goal from user words without generating a summary. */
    public static Focus recover(List<MemoryTypes.Message> recent, RequestContext page) {
        for (int i=recent.size()-1;i>=0;i--) {
            var message=recent.get(i);
            if ("user".equals(message.role()) && !continuation(message.text().strip()))
                return update(message.runId(),message.text(),page,null);
        }
        return null;
    }
    public static Focus update(UUID runId, String message, RequestContext page, Focus prior) {
        String current = message.strip();
        String video = page == null ? null : page.videoId();
        var explicit = DanmakuTarget.matcher(current);
        if (explicit.find()) video = explicit.group(1);
        if (continuation(current) && prior != null && Objects.equals(video, prior.referenceVideoId())) return prior;
        if (current.length() > 500) current = current.substring(0, Character.isHighSurrogate(current.charAt(479)) ? 479 : 480)+" [已截断，仅供指代]";
        return new Focus(current, runId, video);
    }
}
