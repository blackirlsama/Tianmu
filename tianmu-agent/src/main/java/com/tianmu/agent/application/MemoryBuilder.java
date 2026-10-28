package com.tianmu.agent.application;

import com.tianmu.agent.core.MemoryTypes.*;
import java.util.*;

/** A bounded model view, never a mutation of the stored conversation. */
public final class MemoryBuilder {
    private final String boundary;
    public MemoryBuilder() { this(new com.tianmu.agent.prompt.PromptCatalog().text("common.current-focus-boundary")); }
    public MemoryBuilder(String boundary) { this.boundary = Objects.requireNonNull(boundary); }
    public View build(long version, List<Message> source) {
        List<Message> ordered = source.stream().filter(m->Set.of("user","assistant").contains(m.role().toLowerCase(Locale.ROOT)))
                .map(m->new Message(m.id(),m.runId(),m.sequence(),m.role().toLowerCase(Locale.ROOT),m.text(),m.at()))
                .sorted(Comparator.comparingLong(Message::sequence)).toList();
        List<UUID> turns = ordered.stream().map(Message::runId).distinct().toList();
        Set<UUID> selected = new HashSet<>(turns.subList(Math.max(0,turns.size()-2),turns.size()));
        List<Message> recent = new ArrayList<>(ordered.stream().filter(m->selected.contains(m.runId())).toList());
        while (!recent.isEmpty() && recent.stream().mapToInt(m->m.text().length()).sum()>6000) {
            UUID first=recent.get(0).runId();recent.removeIf(m->m.runId().equals(first));
        }
        return new View(version,"",recent,recent.size()<ordered.size(),boundary);
    }
}
