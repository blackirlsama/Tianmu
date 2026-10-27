package com.orangecode.tianmu.agentbridge;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommentAnalysisRangeTest {
    @Test void invalidRangesAreRejectedBeforeDatabaseAccess() {
        DataSource source = mock(DataSource.class);
        var service = new BusinessCommentAnalysis(new JdbcTemplate(source), new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",2));
        var owner = new BusinessAuthentication.Principal("tianmu","881");
        var start = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(1).withNano(0);
        assertEquals(400,assertThrows(BridgeException.class,()->service.query(owner,1001,start,start)).status());
        assertEquals(400,assertThrows(BridgeException.class,()->service.query(owner,1001,start.minusDays(40),start)).status());
        verifyNoInteractions(source);
    }
    @Test void unauthenticatedCommentRequestsCannotReachDataService() {
        var auth = mock(BusinessAuthentication.class); var comments = mock(BusinessCommentAnalysis.class);
        when(auth.authenticate(null)).thenThrow(new BridgeException("UNAUTHORIZED",401));
        var api = new CommentAnalysisController(auth,comments,null);
        assertEquals(401,assertThrows(BridgeException.class,()->api.query(null,1001,LocalDateTime.now(),LocalDateTime.now())).status());
        verifyNoInteractions(comments);
    }
}
