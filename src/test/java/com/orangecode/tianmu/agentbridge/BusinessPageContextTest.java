package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessPageContextTest {
    @Test void metadataIsReadFromTheOwnedVideoAndDoesNotRequireVideoInference() {
        var db=mock(JdbcTemplate.class);var principal=new BusinessAuthentication.Principal("t","10001");
        when(db.getDataSource()).thenReturn(mock(javax.sql.DataSource.class));
        var service=new BusinessMetrics(db,new BridgeProperties("t",Set.of("normal"),"Asia/Shanghai",5));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"db",db);
        when(db.queryForList(contains("user_id=?"),eq(1001L),eq(10001L))).thenReturn(List.of(Map.of("title","动物 · 鸭子","duration",31.698)));
        var page=service.page(principal,1001L);
        assertEquals("1001",page.videoId());assertEquals("动物 · 鸭子",page.title());assertEquals(31698L,page.durationMs());
        assertEquals("FORBIDDEN",assertThrows(BridgeException.class,()->service.page(new BusinessAuthentication.Principal("t","10002"),1001L)).code());
        verify(db).queryForList(contains("is_delete=0"),eq(1001L),eq(10002L));
    }
}
