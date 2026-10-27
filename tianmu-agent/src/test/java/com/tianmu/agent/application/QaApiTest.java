package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.preparation.PreparationModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.profiles.active=demo","harness.trace.directory=./target/qa-api-traces"})
@AutoConfigureMockMvc
@Import(QaApiTest.Fixtures.class)
class QaApiTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;
    @Test void completedIndependentConversationsAutomaticallyAdmitAndThenUseZeroModelCalls()throws Exception{
        http.perform(get("/api/agent/qa")).andExpect(status().isUnauthorized());
        String last=null;
        for(int i=0;i<5;i++){JsonNode run=create();last=run.path("runId").asText();assertEquals("SUCCEEDED",run.path("status").asText());}
        JsonNode catalog=list("demo-alice");long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(catalog.path("items").get(0).path("sessions").asInt()<5 && System.nanoTime()<until){Thread.sleep(20);catalog=list("demo-alice");}
        JsonNode item=catalog.path("items").get(0);assertEquals("ACTIVE",item.path("status").asText());assertEquals(5,item.path("sessions").asInt());
        JsonNode cached=create();assertEquals("FAQ",cached.path("route").asText());assertEquals(0,cached.path("totalModelCalls").asInt());
        assertEquals(5,list("demo-alice").path("items").get(0).path("sessions").asInt());assertEquals(0,list("demo-bob").path("items").size());
        http.perform(post("/api/agent/qa/from-run").header("Authorization","Bearer demo-alice").contentType("application/json").content("{\"runId\":\""+last+"\"}")).andExpect(status().isOk());
        assertEquals(5,list("demo-alice").path("items").get(0).path("sessions").asInt());
        String path="/api/agent/qa/"+item.path("id").asText()+"/disable",body="{\"version\":"+item.path("version").asLong()+"}";
        http.perform(post(path).header("Authorization","Bearer demo-bob").contentType("application/json").content(body)).andExpect(status().isNotFound());
        http.perform(post(path).header("Authorization","Bearer demo-alice").contentType("application/json").content(body)).andExpect(status().isOk());
        http.perform(post(path).header("Authorization","Bearer demo-alice").contentType("application/json").content(body)).andExpect(status().isConflict());
        assertNotEquals("FAQ",create().path("route").asText());
    }
    JsonNode list(String user)throws Exception{return json.readTree(http.perform(get("/api/agent/qa").header("Authorization","Bearer "+user)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray());}
    JsonNode create()throws Exception{
        JsonNode run=json.readTree(http.perform(post("/api/agent/runs").header("Authorization","Bearer demo-alice").contentType("application/json").content("{\"message\":\"如何设计内容选题\"}")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray());
        String id=run.path("runId").asText();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do{run=json.readTree(http.perform(get("/api/agent/runs/"+id).header("Authorization","Bearer demo-alice")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());if(!Set.of("QUEUED","RUNNING").contains(run.path("status").asText()))return run;Thread.sleep(10);}while(System.nanoTime()<until);
        throw new AssertionError("Run did not complete");
    }
    @TestConfiguration static class Fixtures {
        @Bean @Primary ModelPort stableAnswers(){return context->Decision.answer("先明确内容面向的观众，再整理可持续更新的主题，并结合反馈逐步调整。",List.of());}
        @Bean @Primary PreparationModelPort knowledge(){return new PreparationModelPort(){
            public Emotion emotion(Input input,com.tianmu.agent.runtime.control.CallControl control){return Emotion.NEUTRAL;}
            public Intent intent(Input input,com.tianmu.agent.runtime.control.CallControl control){return Intent.KNOWLEDGE;}
            public Understanding understand(Input input,com.tianmu.agent.runtime.control.CallControl control){return Understanding.proceed(Map.of(),input.query());}
        };}
    }
}
