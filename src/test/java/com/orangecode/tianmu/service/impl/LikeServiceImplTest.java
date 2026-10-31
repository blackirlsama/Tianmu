package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.orangecode.tianmu.application.CurrentUser;
import com.orangecode.tianmu.application.VideoLookup;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.Like;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.service.VideoStatsService;
import com.orangecode.tianmu.utils.CounterUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeServiceImplTest {
    private LikeServiceImpl service;
    private MockHttpServletRequest http;
    private VideoStatsService stats;
    private UserService users;
    private CounterUtil counter;
    private VideoLookup videos;

    @BeforeEach void setup() {
        service = spy(new LikeServiceImpl());
        http = new MockHttpServletRequest(); http.setAttribute(CurrentUser.ATTRIBUTE, 1L);
        stats = mock(VideoStatsService.class); users = mock(UserService.class);
        counter = mock(CounterUtil.class); videos = mock(VideoLookup.class);
        ReflectionTestUtils.setField(service, "currentUser", new CurrentUser(http));
        ReflectionTestUtils.setField(service, "videoStatsService", stats);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "counterUtil", counter);
        ReflectionTestUtils.setField(service, "videoLookup", videos);
    }

    @Test void forgedUserIsRejectedBeforeQueriesOrRateLimit() {
        var request = new VideoActionRequest(); request.setUserId(2L); request.setVideoId(99L);
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> service.likeVideo(request));
        verifyNoInteractions(stats, users, counter, videos);
        verify(service, never()).save(any(Like.class));
    }

    @Test void missingIdentityCannotLikeOrCancel() {
        http.removeAttribute(CurrentUser.ATTRIBUTE);
        var request = new VideoActionRequest(); request.setUserId(1L); request.setVideoId(99L);
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> service.likeVideo(request));
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> service.cancelLikeVideo(cancel(99L)));
        verifyNoInteractions(stats, users, counter, videos);
    }

    @Test void otherUsersRecordCannotBeDeleted() {
        record(2L);
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> service.cancelLikeVideo(cancel(99L)));
        verify(service, never()).remove(any(Wrapper.class)); verifyNoInteractions(stats);
    }

    @Test void wrongVideoCannotDeleteRecordOrChangeCounters() {
        record(1L);
        assertCode(ErrorCode.PARAMS_ERROR, () -> service.cancelLikeVideo(cancel(100L)));
        verify(service, never()).remove(any(Wrapper.class)); verifyNoInteractions(stats);
    }

    @Test void alreadyDeletedRecordDoesNotDecrementAgain() {
        record(1L); doReturn(false).when(service).remove(any(Wrapper.class));
        assertCode(ErrorCode.VIDEO_LIKED_NOT_EXISTS, () -> service.cancelLikeVideo(cancel(99L)));
        verifyNoInteractions(stats);
    }

    @Test void missingRecordDoesNotChangeStatistics() {
        doReturn(null).when(service).getById(7L);
        assertCode(ErrorCode.VIDEO_LIKED_NOT_EXISTS, () -> service.cancelLikeVideo(cancel(99L)));
        verify(service, never()).remove(any(Wrapper.class)); verifyNoInteractions(stats);
    }

    @Test void invalidVideoOrRecordIdCannotReachPersistence() {
        var request = new VideoActionRequest(); request.setVideoId(0L);
        assertCode(ErrorCode.PARAMS_ERROR, () -> service.likeVideo(request));
        var cancellation = cancel(99L); cancellation.setId(null);
        assertCode(ErrorCode.PARAMS_ERROR, () -> service.cancelLikeVideo(cancellation));
        verify(service, never()).getById(any(java.io.Serializable.class));
        verifyNoInteractions(stats, users, videos, counter);
    }

    @Test void ownerDeletesOnceAndDecrementsTheStoredVideo() {
        record(1L); doReturn(true).doReturn(false).when(service).remove(any(Wrapper.class));
        var update = update(true);
        assertTrue(service.cancelLikeVideo(cancel(99L)));
        assertCode(ErrorCode.VIDEO_LIKED_NOT_EXISTS, () -> service.cancelLikeVideo(cancel(99L)));
        verify(update).eq(any(), eq(99L));
        verify(update).gt(any(), eq(0));
        verify(update).update();
    }

    @Test void failedStatisticsUpdateRaisesAnErrorForTransactionRollback() {
        record(1L); doReturn(true).when(service).remove(any(Wrapper.class)); update(false);
        assertCode(ErrorCode.SYSTEM_ERROR, () -> service.cancelLikeVideo(cancel(99L)));
    }

    @Test void omittedLegacyUserFieldUsesVerifiedIdentity() {
        var query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(query).eq(any(), any());
        when(users.lambdaQuery()).thenReturn(query); when(query.exists()).thenReturn(true);
        var likes = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(likes).when(likes).eq(any(), any());
        doReturn(likes).when(service).lambdaQuery(); when(likes.exists()).thenReturn(false);
        update(true); doReturn(true).when(service).save(any(Like.class));
        var request = new VideoActionRequest(); request.setVideoId(99L);
        assertNotNull(service.likeVideo(request));
        verify(service).save(argThat((Like value) -> value.getUserId() == 1L && value.getVideoId() == 99L));
    }

    private LambdaUpdateChainWrapper update(boolean success) {
        var update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(update).setSql(anyString(), any(Object[].class));
        doReturn(update).when(update).eq(any(), any());
        doReturn(update).when(update).gt(any(), any());
        when(stats.lambdaUpdate()).thenReturn(update); when(update.update()).thenReturn(success);
        return update;
    }

    private void record(long owner) {
        var value = new Like(); value.setLikeId(7L); value.setUserId(owner); value.setVideoId(99L);
        doReturn(value).when(service).getById(7L);
    }

    private CancelVideoActionRequest cancel(long video) {
        var request = new CancelVideoActionRequest(); request.setId(7L); request.setVideoId(video); return request;
    }

    private void assertCode(ErrorCode code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code.getCode(), assertThrows(BusinessException.class, operation).getCode());
    }
}
