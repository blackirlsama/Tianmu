package com.tianmu.agent.faq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("agent.qa")
public record QaPolicy(@DefaultValue("true") boolean enabled,@DefaultValue("5") int minimumSessions,
        @DefaultValue("0.9") double minimumAnswerShare,@DefaultValue("30") int windowDays,
        @DefaultValue("7") int validDays,@DefaultValue("256") int maximumObservations) {
    public QaPolicy {
        if(minimumSessions<2 || minimumSessions>100 || !Double.isFinite(minimumAnswerShare) || minimumAnswerShare<0.5 || minimumAnswerShare>1
                || windowDays<1 || windowDays>90 || validDays<1 || validDays>30 || maximumObservations<minimumSessions || maximumObservations>256)
            throw new IllegalArgumentException("Invalid Q&A admission policy");
    }
    public static QaPolicy defaults(){return new QaPolicy(true,5,0.9,30,7,256);}
}
