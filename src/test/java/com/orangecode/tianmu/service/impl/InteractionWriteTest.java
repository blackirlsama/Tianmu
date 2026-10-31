package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.orangecode.tianmu.application.CurrentUser;
import com.orangecode.tianmu.application.VideoLookup;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.model.dto.video.*;
import com.orangecode.tianmu.model.entity.*;
import com.orangecode.tianmu.service.*;
import com.orangecode.tianmu.utils.CounterUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"rawtypes", "unchecked"})
class InteractionWriteTest {
    private FavoriteServiceImpl favorites;
    private CoinServiceImpl coins;
    private VideoServiceImpl videos;
    private UserService users;
    private UserStatsService balances;
    private VideoStatsService stats;
    private LikeService likes;
    private VideoLookup lookup;
    private CounterUtil counter;
    private MockHttpServletRequest http;
    private LambdaUpdateChainWrapper balanceUpdate, statsUpdate;

    @BeforeEach void setup() {
        favorites = spy(new FavoriteServiceImpl()); coins = spy(new CoinServiceImpl()); videos = spy(new VideoServiceImpl());
        users = mock(UserService.class); balances = mock(UserStatsService.class);
        stats = mock(VideoStatsService.class); likes = mock(LikeService.class);
        lookup = mock(VideoLookup.class); counter = mock(CounterUtil.class);
        http = new MockHttpServletRequest(); http.setAttribute(CurrentUser.ATTRIBUTE, 1L);
        for (Object service : new Object[]{favorites, coins, videos}) {
            ReflectionTestUtils.setField(service, "currentUser", new CurrentUser(http));
            ReflectionTestUtils.setField(service, "userService", users);
            ReflectionTestUtils.setField(service, "videoStatsService", stats);
        }
        for (Object service : new Object[]{favorites, coins}) ReflectionTestUtils.setField(service, "videoLookup", lookup);
        ReflectionTestUtils.setField(favorites, "counterUtil", counter);
        ReflectionTestUtils.setField(coins, "userStatsService", balances);
        ReflectionTestUtils.setField(videos, "userStatsService", balances);
        ReflectionTestUtils.setField(videos, "likeService", likes);
        ReflectionTestUtils.setField(videos, "favoriteService", favorites);
        ReflectionTestUtils.setField(videos, "coinService", coins);
    }

    @Test void forgedIdentityCannotFavoriteCoinOrTriple() {
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> favorites.favoriteVideo(action(2L)));
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> coins.coinVideo(action(2L)));
        assertCode(ErrorCode.NO_AUTH_ERROR, () -> videos.tripleAction(action(2L)));
        verifyNoInteractions(users, balances, stats, lookup, counter, likes);
        verify(favorites, never()).save(any(Favorite.class)); verify(coins, never()).save(any(Coin.class));
    }

    @Test void absentIdentityCannotExecuteAnyWrite() {
        http.removeAttribute(CurrentUser.ATTRIBUTE);
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> favorites.favoriteVideo(action(1L)));
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> favorites.cancelFavoriteVideo(cancel(99L)));
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> coins.coinVideo(action(1L)));
        assertCode(ErrorCode.NOT_LOGIN_ERROR, () -> videos.tripleAction(action(1L)));
        verifyNoInteractions(users, balances, stats, lookup, counter, likes);
    }

    @Test void cancellationRejectsOtherOwnersAndWrongVideoBeforeDeletion() {
        record(2L); assertCode(ErrorCode.NO_AUTH_ERROR, () -> favorites.cancelFavoriteVideo(cancel(99L)));
        record(1L); assertCode(ErrorCode.PARAMS_ERROR, () -> favorites.cancelFavoriteVideo(cancel(100L)));
        verify(favorites, never()).remove(any(Wrapper.class)); verifyNoInteractions(stats);
    }

    @Test void missingFavoriteOrLostDeleteCannotChangeCounts() {
        doReturn(null).when(favorites).getById(7L);
        assertCode(ErrorCode.VIDEO_FAVORITE_NOT_EXISTS, () -> favorites.cancelFavoriteVideo(cancel(99L)));
        record(1L); doReturn(false).when(favorites).remove(any(Wrapper.class));
        assertCode(ErrorCode.VIDEO_FAVORITE_NOT_EXISTS, () -> favorites.cancelFavoriteVideo(cancel(99L)));
        verifyNoInteractions(stats);
    }

    @Test void successfulCancellationCountsOnceAndRequiresPositiveCount() {
        record(1L); doReturn(true).doReturn(false).when(favorites).remove(any(Wrapper.class));
        statsUpdate = update(); when(stats.lambdaUpdate()).thenReturn(statsUpdate);
        assertTrue(favorites.cancelFavoriteVideo(cancel(99L)));
        assertCode(ErrorCode.VIDEO_FAVORITE_NOT_EXISTS, () -> favorites.cancelFavoriteVideo(cancel(99L)));
        verify(statsUpdate).eq(any(), eq(99L)); verify(statsUpdate).gt(any(), eq(0)); verify(statsUpdate).update();
    }

    @Test void failedCancellationCountUpdateRaisesRollbackError() {
        record(1L); doReturn(true).when(favorites).remove(any(Wrapper.class));
        statsUpdate = update(); when(stats.lambdaUpdate()).thenReturn(statsUpdate); when(statsUpdate.update()).thenReturn(false);
        assertCode(ErrorCode.SYSTEM_ERROR, () -> favorites.cancelFavoriteVideo(cancel(99L)));
    }

    @Test void favoriteUsesVerifiedIdentityWhenLegacyFieldIsOmitted() {
        fixture(false, false, false, 1);
        doReturn(true).when(favorites).save(any(Favorite.class));
        doReturn(query(new VideoStats(), true)).when(stats).lambdaQuery();
        assertNotNull(favorites.favoriteVideo(action(null)));
        verify(favorites).save(argThat((Favorite value) -> value.getUserId() == 1L && value.getVideoId() == 99L));
        verify(counter).incrAndGetCounter(eq("favorite:1:99"), anyInt(), any(), anyLong());
    }

    @Test void coinUsesVerifiedIdentityAndGuardedBalanceUpdate() {
        fixture(false, false, false, 1); doReturn(true).when(coins).save(any(Coin.class));
        assertTrue(coins.coinVideo(action(null)));
        verify(coins).save(argThat((Coin value) -> value.getUserId() == 1L && value.getVideoId() == 99L));
        verify(balanceUpdate).eq(any(), eq(1L)); verify(balanceUpdate).gt(any(), eq(0));
    }

    @Test void coinCannotSpendTheBalanceConsumedByAnotherRequest() {
        fixture(false, false, false, 1); doReturn(true).when(coins).save(any(Coin.class));
        when(balanceUpdate.update()).thenReturn(false);
        assertCode(ErrorCode.USER_COIN_ERROR, () -> coins.coinVideo(action(1L)));
    }

    @Test void missingOrZeroBalanceRejectsCoinBeforeWriting() {
        fixture(false, false, false, 0);
        assertCode(ErrorCode.USER_COIN_ERROR, () -> coins.coinVideo(action(1L)));
        when(balances.getById(1L)).thenReturn(null);
        assertCode(ErrorCode.USER_COIN_ERROR, () -> coins.coinVideo(action(1L)));
        verify(coins, never()).save(any(Coin.class)); verifyNoInteractions(stats);
    }

    @Test void tripleUsesVerifiedIdentityForAllNewRecordsAndGuardsBalance() {
        fixture(false, false, false, 1);
        var result = videos.tripleAction(action(null));
        assertNotNull(result.getLikeId()); assertNotNull(result.getFavoriteId()); assertTrue(result.isCoin());
        verify(likes).save(argThat((Like v) -> v.getUserId() == 1L && v.getVideoId() == 99L));
        verify(favorites).save(argThat((Favorite v) -> v.getUserId() == 1L && v.getVideoId() == 99L));
        verify(coins).save(argThat((Coin v) -> v.getUserId() == 1L && v.getVideoId() == 99L));
        verify(balanceUpdate).gt(any(), eq(0));
    }

    @Test void tripleDoesNotRequireOrSpendAnotherCoinWhenAlreadyCoined() {
        fixture(false, false, true, 0);
        var result = videos.tripleAction(action(1L));
        assertNotNull(result.getLikeId()); assertNotNull(result.getFavoriteId());
        verify(coins, never()).save(any(Coin.class)); verify(balances, never()).lambdaUpdate();
    }

    @Test void tripleRejectsInsufficientBalanceBeforeAnyWrite() {
        fixture(false, false, false, 0);
        assertCode(ErrorCode.USER_COIN_ERROR, () -> videos.tripleAction(action(1L)));
        verify(likes, never()).save(any(Like.class)); verify(favorites, never()).save(any(Favorite.class));
        verify(coins, never()).save(any(Coin.class)); verify(stats, never()).update(any(Wrapper.class));
    }

    @Test void tripleFailsWhenConcurrentSpendingWinsTheLastCoin() {
        fixture(false, false, false, 1); when(balanceUpdate.update()).thenReturn(false);
        assertCode(ErrorCode.USER_COIN_ERROR, () -> videos.tripleAction(action(1L)));
        verify(stats, never()).update(any(Wrapper.class));
    }

    @Test void completedTripleIsRejectedWithoutNewRecordsOrSpending() {
        fixture(true, true, true, 0);
        assertCode(ErrorCode.OPERATION_ERROR, () -> videos.tripleAction(action(1L)));
        verify(likes, never()).save(any(Like.class)); verify(favorites, never()).save(any(Favorite.class));
        verify(coins, never()).save(any(Coin.class)); verify(balances, never()).lambdaUpdate();
    }

    private void fixture(boolean liked, boolean favorited, boolean coined, int balance) {
        User user = new User(); user.setUserId(1L);
        UserStats wallet = new UserStats(); wallet.setUserId(1L); wallet.setCoinCount(balance);
        doReturn(query(user, true)).when(users).lambdaQuery();
        doReturn(query(wallet, true)).when(balances).lambdaQuery(); when(balances.getById(1L)).thenReturn(wallet);
        doReturn(query(null, true)).when(videos).lambdaQuery();
        doReturn(query(null, liked)).when(likes).lambdaQuery();
        doReturn(query(null, favorited)).when(favorites).lambdaQuery();
        doReturn(query(null, coined)).when(coins).lambdaQuery();
        balanceUpdate = update(); when(balances.lambdaUpdate()).thenReturn(balanceUpdate);
        statsUpdate = update(); when(stats.lambdaUpdate()).thenReturn(statsUpdate);
        when(likes.save(any(Like.class))).thenReturn(true);
        doReturn(true).when(favorites).save(any(Favorite.class)); doReturn(true).when(coins).save(any(Coin.class));
        when(stats.update(any(Wrapper.class))).thenReturn(true);
    }

    private LambdaQueryChainWrapper query(Object value, boolean exists) {
        var query = mock(LambdaQueryChainWrapper.class, RETURNS_SELF);
        doReturn(query).when(query).eq(any(), any()); when(query.one()).thenReturn(value); when(query.exists()).thenReturn(exists); return query;
    }
    private LambdaUpdateChainWrapper update() {
        var update = mock(LambdaUpdateChainWrapper.class, RETURNS_SELF);
        doReturn(update).when(update).setSql(anyString(), any(Object[].class));
        doReturn(update).when(update).eq(any(), any()); doReturn(update).when(update).gt(any(), any());
        when(update.update()).thenReturn(true); return update;
    }
    private VideoActionRequest action(Long user) {
        var request = new VideoActionRequest(); request.setUserId(user); request.setVideoId(99L); return request;
    }
    private CancelVideoActionRequest cancel(long video) {
        var request = new CancelVideoActionRequest(); request.setId(7L); request.setVideoId(video); return request;
    }
    private void record(long owner) {
        var record = new Favorite(); record.setFavoriteId(7L); record.setVideoId(99L); record.setUserId(owner);
        doReturn(record).when(favorites).getById(7L);
    }
    private void assertCode(ErrorCode code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code.getCode(), assertThrows(BusinessException.class, operation).getCode());
    }
}
