package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.service.*;
import com.orangecode.tianmu.model.dto.video.CreateCommentRequest;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreatorPublicationTest {
    final JdbcTemplate db = mock(JdbcTemplate.class);
    final CommentService comments = mock(CommentService.class);
    final BulletIngestionService bullets = mock(BulletIngestionService.class);
    final BusinessAuthentication.Principal owner = new BusinessAuthentication.Principal("tianmu","881");
    CreatorPublication service() {
        when(db.queryForList(startsWith("SELECT duration"),eq(1001L),eq(881L))).thenReturn(List.of(Map.of("duration",10.0)));
        return new CreatorPublication(db,comments,bullets);
    }
    CreatorPublication.Batch comment(String id, String text) { return new CreatorPublication.Batch("1001","COMMENT",List.of(new CreatorPublication.Item(id,text,null))); }
    @Test void commentUsesAuthenticatedAuthorAndStableIdAndRetryDoesNotIncrementAgain() {
        var service = service();
        when(db.queryForList(startsWith("SELECT video_id"),eq(123L))).thenReturn(List.of(),List.of(Map.of("video_id",1001L,"user_id",881L,"content","作者补充说明","parent_comment_id",0L)));
        service.publish(owner,comment("123","作者补充说明"));
        service.publish(owner,comment("123","作者补充说明"));
        var request = ArgumentCaptor.forClass(CreateCommentRequest.class);
        verify(comments,times(1)).createAuthorComment(request.capture(),eq(123L));
        assertEquals(881L,request.getValue().getUserId()); assertEquals(1001L,request.getValue().getVideoId());
        assertNull(request.getValue().getParentCommentId());
    }
    @Test void danmakuUsesExistingIngestionAndConvertsMillisecondsToSeconds() {
        var service = service();
        service.publish(owner,new CreatorPublication.Batch("1001","DANMAKU",List.of(new CreatorPublication.Item("456","鸭子出场",1500L))));
        var request = ArgumentCaptor.forClass(SendBulletRequest.class); verify(bullets).persist(request.capture());
        assertEquals(881L,request.getValue().getUserId()); assertEquals(456L,request.getValue().getBulletId());
        assertEquals(1.5,request.getValue().getPlaybackTime()); assertNotNull(request.getValue().getCreateTime());
        assertThrows(BridgeException.class,()->service.publish(owner,new CreatorPublication.Batch("1001","DANMAKU",List.of(new CreatorPublication.Item("457","超出结尾",10000L)))));
        verifyNoMoreInteractions(bullets);
    }
    @Test void changedOwnershipAndConflictingIdsAreRejected() {
        var service = service();
        when(db.queryForList(startsWith("SELECT duration"),eq(1001L),eq(881L))).thenReturn(List.of());
        assertEquals("FORBIDDEN",assertThrows(BridgeException.class,()->service.publish(owner,comment("123","留言"))).code());
        verifyNoInteractions(comments,bullets);
        var secondService = service();
        when(db.queryForList(startsWith("SELECT video_id"),eq(123L))).thenReturn(List.of(Map.of("video_id",1001L,"user_id",999L,"content","留言","parent_comment_id",0L)));
        var target = secondService;
        assertEquals("DRAFT_CONFLICT",assertThrows(BridgeException.class,()->target.publish(owner,comment("123","留言"))).code());
    }
    @Test void wholeBatchRollsBackWhenLaterItemIsInvalid() throws Exception {
        var target = service();
        DataSource source = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        var service = (CreatorPublication)proxy.getProxy();
        var batch = new CreatorPublication.Batch("1001","COMMENT",List.of(new CreatorPublication.Item("123","第一条",null),new CreatorPublication.Item("456"," ",null)));
        assertThrows(BridgeException.class,()->service.publish(owner,batch));
        verify(connection).rollback(); verify(connection,never()).commit(); verify(connection).close();
    }
}
