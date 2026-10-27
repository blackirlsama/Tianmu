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
