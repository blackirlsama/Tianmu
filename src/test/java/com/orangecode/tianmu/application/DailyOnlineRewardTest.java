package com.orangecode.tianmu.application;

import com.orangecode.tianmu.exception.BusinessException;
import com.orangecode.tianmu.controller.OnlineRewardController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DailyOnlineRewardTest {
    @Test void dayUsesBeijingMidnightInsteadOfHostTimezone() {
        for (String instant : List.of("2026-10-31T15:59:59Z", "2026-10-31T16:00:00Z")) {
            var db = mock(JdbcTemplate.class);
            when(db.queryForList(anyString(), eq(Integer.class), eq(1L))).thenReturn(List.of(6));
            LocalDate day = instant.contains("15:59") ? LocalDate.of(2026,10,31) : LocalDate.of(2026,11,1);
            var result = new DailyOnlineReward(db, Clock.fixed(Instant.parse(instant), ZoneOffset.ofHours(-7))).visit(1L);
            assertEquals(day, result.day()); assertEquals(6, result.coinCount());
            verify(db).update(anyString(), eq(day), eq(1L), eq(day));
        }
    }

    @Test void missingAccountFailsInsteadOfReportingAReward() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), eq(Integer.class), eq(1L))).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> new DailyOnlineReward(db).visit(1L));
    }

    @Test void endpointRequiresVerifiedIdentityAndNeverUsesClientUserIds() {
        var request = new MockHttpServletRequest(); request.setParameter("userId", "2");
        var rewards = mock(DailyOnlineReward.class);
        var controller = new OnlineRewardController(new CurrentUser(request), rewards);
        assertThrows(BusinessException.class, controller::online); verifyNoInteractions(rewards);
        request.setAttribute(CurrentUser.ATTRIBUTE, 1L);
        controller.online(); verify(rewards).visit(1L); verify(rewards, never()).visit(2L);
    }
}
