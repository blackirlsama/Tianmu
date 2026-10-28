package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.orangecode.tianmu.model.entity.Comment;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CommentServiceImplTest {
    @Test void creationSuppliesCurrentInstantInsteadOfDatabaseTimezoneDefault() {
        var service = spy(new CommentServiceImpl());
        var users = mock(UserService.class);
        var stats = mock(com.orangecode.tianmu.service.VideoStatsService.class);
        var update = mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class, RETURNS_SELF);
        var query = mock(com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(update).setSql(anyString(),any(Object[].class));
        doReturn(update).when(update).eq(any(),any());
        doReturn(query).when(query).eq(any(),any());
        when(stats.lambdaUpdate()).thenReturn(update); when(update.update()).thenReturn(true);
        when(users.lambdaQuery()).thenReturn(query); when(query.one()).thenReturn(user(1));
        ReflectionTestUtils.setField(service,"userService",users); ReflectionTestUtils.setField(service,"videoStatsService",stats);
        var saved = new java.util.concurrent.atomic.AtomicReference<Comment>();
        doAnswer(call -> { saved.set(call.getArgument(0)); return true; }).when(service).save(any(Comment.class));
        doAnswer(call -> saved.get()).when(service).getById(any(java.io.Serializable.class));
        var request = new com.orangecode.tianmu.model.dto.video.CreateCommentRequest();
        request.setContent("作者留言"); request.setUserId(1L); request.setVideoId(99L);
        long before = System.currentTimeMillis();
        var result = service.createAuthorComment(request,123L);
        assertNotNull(saved.get().getCreateTime());
        assertTrue(saved.get().getCreateTime().getTime() >= before);
        assertTrue(saved.get().getCreateTime().getTime() <= System.currentTimeMillis());
        assertEquals(saved.get().getCreateTime(),result.getCreateTime());
    }
    @Test void rootAndReplyTimestampsCarryExplicitOffsetAcrossServerTimezones() throws Exception {
        var instant = Date.from(java.time.Instant.parse("2026-10-28T16:38:24Z"));
        var root = new com.orangecode.tianmu.model.vo.video.CommentVideoResponse();root.setCreateTime(instant);
        var reply = new com.orangecode.tianmu.model.vo.video.CommentResponse();reply.setCreateTime(instant);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (Object response : List.of(root,reply))
            assertEquals("2026-10-29T00:38:24+08:00",mapper.readTree(mapper.writeValueAsString(response)).get("createTime").asText());
    }
    @Test
    void listsZeroAndNullRootsWithDirectAndNestedReplies() {
        var service = spy(new CommentServiceImpl());
        var users = mock(UserService.class);
        ReflectionTestUtils.setField(service, "userService", users);
        doReturn(List.of(comment(11, 0L, 1), comment(12, null, 2),
                comment(13, 11L, 3), comment(14, 13L, 4)))
                .when(service).list(any(Wrapper.class));
        when(users.listByIds(any())).thenReturn(List.of(user(1), user(2), user(3), user(4)));

        var result = service.getCommentVideoList(99L);

        assertEquals(List.of(12L, 11L), result.stream().map(r -> r.getCommentId()).toList());
        assertTrue(result.get(0).getChildren().isEmpty());
        var replies = result.get(1).getChildren();
        assertEquals(List.of(14L, 13L), replies.stream().map(r -> r.getCommentId()).toList());
        assertEquals(3L, replies.get(0).getToUserId());
        assertEquals("user3", replies.get(0).getToNickname());
        assertEquals(1L, replies.get(1).getToUserId());
        assertEquals("user1", replies.get(1).getToNickname());
        verify(users).listByIds(java.util.Set.of(1L, 2L, 3L, 4L));
    }

    private Comment comment(long id, Long parentId, long userId) {
        var comment = new Comment();
        comment.setCommentId(id);
        comment.setVideoId(99L);
        comment.setParentCommentId(parentId);
        comment.setUserId(userId);
        comment.setContent("comment" + id);
        comment.setCreateTime(new Date(id * 1000));
        return comment;
    }

    private User user(long id) {
        var user = new User();
        user.setUserId(id);
        user.setNickname("user" + id);
        return user;
    }
}
