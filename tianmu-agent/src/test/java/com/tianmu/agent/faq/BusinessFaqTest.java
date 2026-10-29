package com.tianmu.agent.faq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.PreparationTypes.Input;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.runtime.control.CallControl;
import com.tianmu.agent.runtime.control.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessFaqTest {
    final AccessScope owner=new AccessScope("tianmu-site","author",Set.of());
    CallControl control(){return new CallControl(Instant.now().plusSeconds(10),new CancellationToken());}
    Input input(String question){return new Input(question,LocalDate.of(2026,10,29),owner);}
    @Test void everyConfiguredQuestionReturnsItsApprovedAnswer(){
        var resource=new ClassPathResource("faq/business.json");var faq=new ClasspathFaqPort(resource,null);
        var entries=ResourceFiles.json(resource,ClasspathFaqPort.Entry[].class);assertEquals(15,entries.length);
        Set<String> questions=new HashSet<>();
        for(var entry:entries)for(String question:entry.questions()){
            assertTrue(questions.add(question));var found=faq.find(input(" "+question+" "),control()).orElseThrow();
            assertEquals(entry.id(),found.id());assertEquals(entry.version(),found.version());assertEquals(entry.text(),found.text());
        }
    }
    @Test void dynamicAndPersonalizedRequestsNeverUseGenericConfiguredAnswers(){
        var faq=new ClasspathFaqPort(new ClassPathResource("faq/business.json"),null);
        for(String question:List.of("这个视频有多少条弹幕","帮我生成观众反馈报告","分析昨天的评论","你能做什么？顺便分析这个视频","根据这个视频给下一条视频一些灵感"))
            assertTrue(faq.find(input(question),control()).isEmpty(),question);
    }
    @Test void externalConfigurationReplacesBundledQuestionsAndReloadsOnNewInstance(@TempDir Path directory)throws Exception{
        Path file=directory.resolve("business.json");var json=new ObjectMapper();
        var entry=new ClasspathFaqPort.Entry("custom-help","1",List.of("如何使用运营助手"),"这是由运营人员维护的人工问答，请先说明创作目标。");
        json.writeValue(file.toFile(),new ClasspathFaqPort.Entry[]{entry});
        var faq=new ClasspathFaqPort(new FileSystemResource(file),null);
        assertEquals(entry.text(),faq.find(input(entry.questions().get(0)),control()).orElseThrow().text());
        assertTrue(faq.find(input("你能做什么"),control()).isEmpty());
        var updated=new ClasspathFaqPort.Entry(entry.id(),"2",entry.questions(),"更新后的人工回答，请选择需要帮助的具体内容。");
        json.writeValue(file.toFile(),new ClasspathFaqPort.Entry[]{updated});
        assertEquals("1",faq.find(input(entry.questions().get(0)),control()).orElseThrow().version());
        var reloaded=new ClasspathFaqPort(new FileSystemResource(file),null);
        assertEquals("2",reloaded.find(input(entry.questions().get(0)),control()).orElseThrow().version());
        Files.writeString(file,"[]");assertThrows(IllegalStateException.class,()->new ClasspathFaqPort(new FileSystemResource(file),null));
        Files.writeString(file,"{invalid");assertThrows(IllegalStateException.class,()->new ClasspathFaqPort(new FileSystemResource(file),null));
        Files.delete(file);assertThrows(IllegalStateException.class,()->new ClasspathFaqPort(new FileSystemResource(file),null));
    }
}
