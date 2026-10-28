package com.tianmu.agent.application;

import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {
    @Test void ownQuestionsSurviveNarrowerScopeButVideoAnswersAreRedactedAndOtherAccountsCannotReadHistory() {
        var store=new MemoryRunRepository();var owner=new AccessScope("t","1",Set.of(1001L));
        var first=store.create("给下一条视频一点灵感",owner,null,null,null,new RequestContext("1001")).run();
        first.start();first.prepared(new Result(Route.FAQ,new PreparedRequest(first.message,first.query(),Emotion.UNKNOWN,Set.of()),new FaqAnswer("fixture","1","private video facts"),null));
        first.succeed(first.reserveFinalResponse(),new Answer("private video facts",List.of()),StopReason.FAQ_ANSWER);
        var narrower=new AccessScope("t","1",Set.of());
        var next=store.create("继续",narrower,first.sessionId,null,null,null).run();
        var memory=store.memory(next);
        assertEquals("给下一条视频一点灵感",memory.recent().get(0).text());
        assertEquals("[先前视频结果当前无权限，已隐藏]",memory.recent().get(1).text());
        assertThrows(AgentException.class,()->store.messages(first.sessionId,new AccessScope("t","other",Set.of(1001L)),Long.MAX_VALUE));
    }
}
