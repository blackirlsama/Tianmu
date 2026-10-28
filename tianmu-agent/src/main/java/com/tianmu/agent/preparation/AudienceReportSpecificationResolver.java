package com.tianmu.agent.preparation;
import com.tianmu.agent.core.TaskTypes.Specification;
import com.tianmu.agent.core.PreparationTypes.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class AudienceReportSpecificationResolver implements TaskSpecificationResolver,QueryReadinessPolicy {
    public Optional<Specification> resolve(String text,LocalDate date){return resolve(text,date,date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());}
    public Optional<Specification> resolve(String text,LocalDate date,Instant at){
        if(!AudienceReportTask.requested(text))return Optional.empty();
        try{var spec=DanmakuReportTask.specification(DanmakuReportTask.parse(text,date,at.atZone(ZoneId.of("Asia/Shanghai"))));return Optional.of(new Specification("audience.report",spec.conditions(),spec.provenance()));}
        catch(RuntimeException e){return Optional.empty();}
    }
    public Optional<Clarification> validate(Input input){
        if(!AudienceReportTask.requested(input.targetedQuery()))return Optional.empty();
        try{DanmakuReportTask.parse(input.targetedQuery(),input.businessDate(),input.requestedAt().atZone(ZoneId.of("Asia/Shanghai"))).checkTime(Instant.now());return Optional.empty();}
        catch(RuntimeException e){return Optional.of(new Clarification(true,"请明确一个本人视频及有效时间区间；未指定时默认全量弹幕和评论。"));}
    }
}
