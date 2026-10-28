package com.tianmu.agent.interaction;

import com.tianmu.agent.core.AgentException;
import java.util.*;

/** Immutable publication payloads; IDs are strings so browsers never round 64-bit IDs. */
public record CreatorDraft(UUID runId, String tenantId, String userId, String videoId, String kind,
                           String state, List<Item> items) {
    public record Item(String id, String text, Long playbackMs) {}
    public CreatorDraft { items = List.copyOf(items); }
    public CreatorDraft with(String state, List<Item> items) {
        return new CreatorDraft(runId, tenantId, userId, videoId, kind, state, items);
    }
    public static void validate(String kind, List<Item> items) {
        if (!Set.of("COMMENT", "DANMAKU").contains(kind) || items == null || items.isEmpty() || items.size() > 10)
            throw invalid();
        Set<String> ids = new HashSet<>(), texts = new HashSet<>();
        for (Item item : items) {
            if (item == null || item.id() == null || !item.id().matches("[1-9][0-9]{0,18}")
                    || !ids.add(item.id()) || item.text() == null || item.text().isBlank()
                    || !item.text().equals(item.text().strip()) || item.text().codePoints().anyMatch(Character::isISOControl)
                    || item.text().length() > (kind.equals("COMMENT") ? 500 : 80)
                    || !texts.add(item.text().replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                    || kind.equals("COMMENT") && item.playbackMs() != null
                    || kind.equals("DANMAKU") && (item.playbackMs() == null || item.playbackMs() < 0 || item.playbackMs() >= 3_600_000))
                throw invalid();
            try { Long.parseLong(item.id()); } catch (NumberFormatException e) { throw invalid(); }
        }
    }
    private static AgentException invalid() { return new AgentException("INVALID_DRAFT", "草稿内容、数量或弹幕时间不符合要求。"); }
}
