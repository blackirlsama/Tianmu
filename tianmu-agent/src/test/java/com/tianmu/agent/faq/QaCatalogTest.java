package com.tianmu.agent.faq;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.Input;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class QaCatalogTest {
    static final Instant NOW=Instant.parse("2026-10-27T12:00:00Z");
    final MemoryQaStore store=new MemoryQaStore();
    final AccessScope owner=new AccessScope("tenant","alice",Set.of());
    final String question="如何安排内容选题",answer="先明确内容面向的观众，再整理可持续更新的主题，并结合反馈逐步调整。";
    QaCatalog catalog(Instant at){return new QaCatalog(store,QaPolicy.defaults(),Clock.fixed(at,ZoneOffset.UTC));}
    void vote(QaCatalog c,String text){c.observe(owner,UUID.randomUUID(),UUID.randomUUID(),question,text,NOW);}
    Input input(AccessScope scope){return new Input(question+"？",LocalDate.of(2026,10,27),scope);}
    @Test void independentSessionThresholdActivatesWithoutReinforcingFaqHits(){
        var c=catalog(NOW);UUID same=UUID.randomUUID();
        for(int i=0;i<20;i++)c.observe(owner,same,UUID.randomUUID(),question,answer,NOW);
        assertEquals(1,c.list(owner).get(0).sessions());assertTrue(c.find(input(owner)).isEmpty());
        for(int i=0;i<3;i++)vote(c,answer);assertTrue(c.find(input(owner)).isEmpty());
        vote(c,answer);assertEquals(answer,c.find(input(owner)).orElseThrow().text());
        for(int i=0;i<10;i++)c.find(input(owner));assertEquals(5,c.list(owner).get(0).sessions());
        assertTrue(c.find(input(new AccessScope("tenant","bob",Set.of()))).isEmpty());
        assertTrue(c.list(new AccessScope("other","alice",Set.of())).isEmpty());
    }
    @Test void competingAnswersMustReachTheConfiguredShare(){
        var c=catalog(NOW);for(int i=0;i<5;i++)vote(c,answer);
        vote(c,"先观察创作者擅长的表达方式，再尝试不同主题，并保留观众愿意继续讨论的方向。");
        assertTrue(c.find(input(owner)).isEmpty());
        for(int i=0;i<4;i++)vote(c,answer);assertEquals(.9,c.list(owner).get(0).answerShare());assertTrue(c.find(input(owner)).isPresent());
    }
    @Test void disabledEntriesStayDisabledAndResetRequiresFreshObservations(){
        var c=catalog(NOW);for(int i=0;i<5;i++)vote(c,answer);var view=c.list(owner).get(0);
        var disabled=c.change(owner,view.id(),view.version(),false);assertEquals("DISABLED",disabled.status());
        assertThrows(AgentException.class,()->c.change(owner,view.id(),view.version(),true));
        vote(c,answer);assertTrue(c.find(input(owner)).isEmpty());assertEquals(5,c.list(owner).get(0).sessions());
        var reset=c.change(owner,view.id(),disabled.version(),true);assertEquals(0,reset.sessions());
        for(int i=0;i<4;i++)vote(c,answer);assertTrue(c.find(input(owner)).isEmpty());vote(c,answer);assertTrue(c.find(input(owner)).isPresent());
        assertEquals("QA_NOT_FOUND",assertThrows(AgentException.class,()->c.change(new AccessScope("tenant","bob",Set.of()),view.id(),0,false)).code());
    }
    @Test void expiryAndDynamicBusinessAnswersNeverShortCircuitDataTools(){
        var c=catalog(NOW);for(int i=0;i<5;i++)vote(c,answer);
        assertTrue(catalog(NOW.plus(Duration.ofDays(8))).find(input(owner)).isEmpty());
        assertTrue(catalog(NOW.plus(Duration.ofDays(31))).find(input(owner)).isEmpty());
        for(String q:List.of("昨天的弹幕有多少","分析视频的观众报告","如何整理我的评论","刚才说的怎么做"))
            c.observe(owner,UUID.randomUUID(),UUID.randomUUID(),q,answer,NOW);
        c.observe(owner,UUID.randomUUID(),UUID.randomUUID(),"如何统计互动反馈","本次视频共有一百条弹幕，观众表现非常积极。",NOW);
        assertEquals(1,c.list(owner).size());
        c.observe(owner,UUID.randomUUID(),UUID.randomUUID(),"如何选择表达方式","目前有100位粉丝参与讨论，建议继续发布。",NOW);
        assertEquals(1,c.list(owner).size());
    }
    @Test void concurrentReplaysCountTheSessionOnlyOnce() throws Exception {
        var c=catalog(NOW);UUID session=UUID.randomUUID();var pool=Executors.newFixedThreadPool(8);
        try{List<Future<?>> futures=new ArrayList<>();for(int i=0;i<32;i++)futures.add(pool.submit(()->c.observe(owner,session,UUID.randomUUID(),question,answer,NOW)));
            for(var f:futures)f.get(5,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();}
        assertEquals(1,c.list(owner).get(0).sessions());assertTrue(c.find(input(owner)).isEmpty());
    }
    @Test void normalizationPreservesQuestionMeaningAndPolicyRejectsInvalidThresholds(){
        assertEquals(QaCatalog.normalize("内容选题是什么？"),QaCatalog.normalize(" 内容选题是什么? "));
        assertNotEquals(QaCatalog.normalize("如何选择主题"),QaCatalog.normalize("如何选择标题"));
        assertThrows(IllegalArgumentException.class,()->new QaPolicy(true,1,.9,30,7,256));
        assertThrows(IllegalArgumentException.class,()->new QaPolicy(true,5,Double.NaN,30,7,256));
    }
}
