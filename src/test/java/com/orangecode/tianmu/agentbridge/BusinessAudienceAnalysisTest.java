package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessAudienceAnalysisTest {
    @Test void fullHistoryIsExplicitAndDoesNotRelaxOrdinaryRangeValidation(){
        var end=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1).withNano(0);
        assertDoesNotThrow(()->BusinessDanmakuAnalysis.validateRange(1001,LocalDateTime.of(1970,1,1,0,0),end));
        assertThrows(BridgeException.class,()->BusinessDanmakuAnalysis.validateRange(1001,end.minusDays(90),end));
        assertThrows(BridgeException.class,()->BusinessDanmakuAnalysis.validateRange(1001,LocalDateTime.of(1970,1,1,0,0),end.plusDays(1)));
    }
    @Test void bothReadersRunInsideOneReadOnlyRepeatableReadTransaction() throws Exception {
        var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        var danmaku=mock(BusinessDanmakuAnalysis.class);var comments=mock(BusinessCommentAnalysis.class);
        var principal=new BusinessAuthentication.Principal("tianmu","881");var end=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1).withNano(0);var start=end.minusDays(7);
        when(danmaku.query(principal,1001,start,end)).thenAnswer(call->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            assertSame(connection,((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).getConnection());
            return null;
        });
        when(comments.query(principal,1001,start,end)).thenAnswer(call->{
            assertSame(connection,((ConnectionHolder)TransactionSynchronizationManager.getResource(source)).getConnection());return null;
        });
        var db=mock(JdbcTemplate.class);when(db.getDataSource()).thenReturn(source);
        when(db.queryForObject(anyString(),eq(String.class),eq("bullet"))).thenReturn("timestamp");
        when(db.queryForObject(anyString(),eq(String.class),eq("comment"))).thenReturn("timestamp");
        when(db.queryForObject(startsWith("SELECT COUNT(DISTINCT"),eq(Long.class),eq(1001L),anyString(),anyString(),eq(1001L),anyString(),anyString(),eq(881L))).thenReturn(0L);
        new BusinessAudienceAnalysis(db,new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",5),danmaku,comments).query(principal,1001,start,end);
        var order=inOrder(danmaku,comments);order.verify(danmaku).query(principal,1001,start,end);order.verify(comments).query(principal,1001,start,end);
        verify(source,times(1)).getConnection();verify(connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).commit();verify(connection).close();assertFalse(TransactionSynchronizationManager.hasResource(source));
    }
}
