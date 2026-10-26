package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.query.DanmakuQueryPort;
import com.tianmu.agent.model.AudienceModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.profiles.active=demo","agent.storage.mode=memory","agent.video.enabled=false","harness.trace.directory=./target/room-api-traces"})
@AutoConfigureMockMvc
@Import(RoomApiTest.Fixtures.class)
class RoomApiTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;
    @Test void roomRequiresCurrentVideoPermissionAndDoesNotCachePrivateResponses() throws Exception {
        http.perform(get("/api/agent/rooms/1001/summary")).andExpect(status().isUnauthorized());
        http.perform(get("/api/agent/rooms/1001/summary").header("Authorization","Bearer demo-bob")).andExpect(status().isForbidden());
        JsonNode view=null;long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do{
            byte[] body=http.perform(get("/api/agent/rooms/1001/summary").header("Authorization","Bearer demo-alice"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
            view=json.readTree(body);if(view.path("state").asText().equals("READY"))break;Thread.sleep(20);
        }while(System.nanoTime()<until);
        assertEquals("READY",view.path("state").asText());assertEquals(2,view.path("data").path("totalMessages").asLong());
        assertEquals(2,view.path("topics").get(0).path("messages").asLong());
        assertFalse(view.path("videoAnalyzed").asBoolean());
        assertFalse(view.toString().contains("authorizationRef"));
    }
    @Test void invalidStartIsRejectedBeforeAnySummaryWork() throws Exception {
        http.perform(get("/api/agent/rooms/1001/summary").param("start",LocalDateTime.now().plusDays(5).toString()).header("Authorization","Bearer demo-alice"))
                .andExpect(status().isBadRequest());
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary DanmakuQueryPort roomData(){return (q,scope,c)->new Dataset(q,2,1,
                java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?2:0)).toList(),List.of(),Instant.now(),"fixture",true,
                new Overview(List.of(new Interaction("42",2,1,1,Instant.now())),List.of(new Position(0,30000,2)),0,List.of(new TextGroup("1","尺寸？",2,2500L,false)),2,2));}
        @Bean AudienceModelPort topics(){return (data,previous,video,control)->"[{\"title\":\"尺寸提问\",\"observation\":\"观众询问尺寸\",\"recommendation\":\"补充尺寸说明\",\"sample_ids\":[\"1\"]}]";}
    }
}
