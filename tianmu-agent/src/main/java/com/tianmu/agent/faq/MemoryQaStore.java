package com.tianmu.agent.faq;

import com.github.benmanes.caffeine.cache.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.time.Duration;
import java.util.*;
import java.util.function.UnaryOperator;

@Component
@ConditionalOnProperty(name="agent.storage.mode",havingValue="memory",matchIfMissing=true)
public final class MemoryQaStore implements QaStore {
    private record Key(String owner,String question) {}
    private final Cache<Key,QaState> states=Caffeine.newBuilder().maximumSize(1024).expireAfterAccess(Duration.ofDays(90)).build();
    public QaState get(String owner,String question){return states.getIfPresent(new Key(owner,question));}
    public List<QaState.Item> list(String owner,int limit){return states.asMap().entrySet().stream().filter(e->e.getKey().owner().equals(owner))
            .sorted(Map.Entry.<Key,QaState>comparingByValue(Comparator.comparing(QaState::updatedAt)).reversed())
            .limit(limit).map(e->new QaState.Item(e.getKey().question(),e.getValue())).toList();}
    public QaState update(String owner,String question,QaState initial,UnaryOperator<QaState> change){
        return states.asMap().compute(new Key(owner,question),(key,old)->old==null && initial==null?null:change.apply(old==null?initial:old));
    }
}
