package com.tianmu.agent.application;

import com.tianmu.agent.interaction.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreatorPublicationTest {
    final AccessScope scope = new AccessScope("t","1",Set.of(1001L));
    CreatorDraft draft(String kind) { return new CreatorDraft(UUID.randomUUID(),"t","1","1001",kind,"DRAFT",List.of(new CreatorDraft.Item("123","原始草稿",kind.equals("COMMENT")?null:1000L))); }
    @Test void uncertainResponseFreezesPayloadAndRetryUsesStableIds() {
        var store = new MemoryCreatorDraftStore(); var port = mock(CreatorPublicationPort.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("port",port);
        var service = new CreatorPublicationService(store,beans.getBeanProvider(CreatorPublicationPort.class));
        var draft = draft("COMMENT"); store.create(draft);
        var edited = List.of(new CreatorDraft.Item("123","作者编辑后的内容",null));
        doThrow(new AgentException("BUSINESS_TIMEOUT","timeout")).doNothing().when(port).publish(any(),any());
        assertThrows(AgentException.class,()->service.confirm(draft.runId(),edited,scope));
        assertEquals("PUBLISHING",store.get(draft.runId()).state());
        assertEquals(edited,store.get(draft.runId()).items());
        assertThrows(AgentException.class,()->service.confirm(draft.runId(),draft.items(),scope));
        assertEquals("PUBLISHED",service.confirm(draft.runId(),edited,scope).state());
        assertEquals("PUBLISHED",service.confirm(draft.runId(),edited,scope).state());
        verify(port,times(2)).publish(any(),eq(scope));
    }
    @Test void foreignIdsForeignOwnersEmptyDuplicateAndInvalidTimesCannotPublish() {
        var store = new MemoryCreatorDraftStore(); var beans = new StaticListableBeanFactory();
        var service = new CreatorPublicationService(store,beans.getBeanProvider(CreatorPublicationPort.class));
        var draft = draft("DANMAKU"); store.create(draft);
        assertThrows(AgentException.class,()->service.confirm(draft.runId(),List.of(),scope));
        assertThrows(AgentException.class,()->service.confirm(draft.runId(),List.of(new CreatorDraft.Item("999","替换ID",1000L)),scope));
        assertThrows(AgentException.class,()->service.owned(draft.runId(),new AccessScope("t","2",Set.of(1001L))));
        assertThrows(AgentException.class,()->service.owned(draft.runId(),new AccessScope("t","1",Set.of(1002L))));
        assertThrows(AgentException.class,()->CreatorDraft.validate("DANMAKU",List.of(new CreatorDraft.Item("1","test",-1L))));
        assertThrows(AgentException.class,()->CreatorDraft.validate("COMMENT",List.of(new CreatorDraft.Item("1","同文",null),new CreatorDraft.Item("2","同 文",null))));
        assertThrows(AgentException.class,()->CreatorDraft.validate("COMMENT",List.of(new CreatorDraft.Item("1","test",1000L))));
        assertEquals("DRAFT",store.get(draft.runId()).state());
    }
    @Test void definiteRejectionAllowsCorrectionButDoesNotMarkAnythingPublished() {
        var store = new MemoryCreatorDraftStore(); var port = mock(CreatorPublicationPort.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("port",port);
        var service = new CreatorPublicationService(store,beans.getBeanProvider(CreatorPublicationPort.class));
        var draft = draft("DANMAKU"); store.create(draft);
        doThrow(new AgentException("INVALID_DRAFT","outside duration")).doNothing().when(port).publish(any(),any());
        assertThrows(AgentException.class,()->service.confirm(draft.runId(),draft.items(),scope));
        assertEquals("DRAFT",store.get(draft.runId()).state());
        var corrected = List.of(new CreatorDraft.Item("123","修改时间后的弹幕",500L));
        assertEquals("PUBLISHED",service.confirm(draft.runId(),corrected,scope).state());
    }
}
