package com.orangecode.tianmu.agentbridge;

import io.minio.MinioClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessVideoContentTest {
    @Test void unauthorizedVideoNeverReachesObjectStorage() {
        var db=mock(JdbcTemplate.class); var minio=mock(MinioClient.class);
        var service=new BusinessVideoContent(db,minio,"tianmu-upload");
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of());
        assertEquals(403,assertThrows(BridgeException.class,()->service.source(new BusinessAuthentication.Principal("tenant","1"),7)).status());
        verifyNoInteractions(minio);
    }
    @Test void arbitraryMediaUrlsCannotBeFetched() {
        var db=mock(JdbcTemplate.class); var minio=mock(MinioClient.class);
        when(db.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("file_url","http://127.0.0.1/private.mp4","duration",10)));
        var service=new BusinessVideoContent(db,minio,"tianmu-upload");
        assertEquals(422,assertThrows(BridgeException.class,()->service.source(new BusinessAuthentication.Principal("tenant","1"),7)).status());
        verifyNoInteractions(minio);
    }
}
