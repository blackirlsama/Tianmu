package com.tianmu.agent;

import com.tianmu.agent.application.RequestMessages;
import com.tianmu.agent.application.ClarificationInputs;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequestMessagesTest {
    @Test void contextDoesNotPolluteFaqAndExplicitTargetsWin() {
        var page = new RequestContext("1001");
        assertEquals("你能做什么", RequestMessages.execution("你能做什么", page));
        var legacy = RequestMessages.normalize("你能做什么\n\n[页面上下文：当前视频1001。]", null);
        assertEquals("你能做什么", legacy.message()); assertEquals(page, legacy.context());
        assertEquals("查询视频1002昨天的弹幕数", RequestMessages.execution("查询视频1002昨天的弹幕数", page));
        assertEquals("昨天有多少条弹幕？", RequestMessages.execution("昨天有多少条弹幕？", page));
        assertEquals("昨天有多少条弹幕？\n视频1001",RequestMessages.forParsing("昨天有多少条弹幕？",page));
        for (String advice : List.of("如何提高弹幕互动？", "怎么分析弹幕？", "解释一下弹幕数是什么意思")) {
            assertEquals(advice, RequestMessages.execution(advice, page));
            assertEquals("unclassified", com.tianmu.agent.preparation.TaskMeaning.defaults().resolve(advice, java.time.LocalDate.of(2026, 10, 25)).type());
        }
        assertFalse(ConversationTopics.general("请分析昨天的弹幕，再给我运营建议"));
        assertThrows(AgentException.class, () -> RequestMessages.normalize("你好\n\n[页面上下文：当前视频1001。]", new RequestContext("1002")));
        for (String invalid : List.of("0", "-1", "01", "1.0", "9223372036854775808"))
            assertThrows(AgentException.class, () -> new RequestContext(invalid));
    }
    @Test void ephemeralRequestsPreserveWordsAndIncludeContextInIdempotency() {
        var repo = new MemoryRunRepository(); var owner = new AccessScope("demo", "alice", Set.of(1001L, 1002L));
        var page = new RequestContext("1001");
        var first = repo.create("昨天有多少条弹幕？", owner, null, "key", null, page);
        assertEquals("昨天有多少条弹幕？", first.run().message);
        assertEquals("昨天有多少条弹幕？",first.run().query());
        assertEquals(page,first.run().pageContext());
        assertFalse(repo.create("昨天有多少条弹幕？", owner, null, "key", null, page).fresh());
        assertThrows(AgentException.class, () -> repo.create("昨天有多少条弹幕？", owner, null, "key", null, new RequestContext("1002")));
        assertEquals("帮我写开场白\n用户补充：轻松一点", ClarificationInputs.combine("帮我写开场白", "轻松一点"));
    }
}
