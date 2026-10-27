package com.tianmu.agent.faq;

import java.time.Instant;
import java.util.*;

/** A bounded, owner-private question aggregate; admission is never a model confidence score. */
public record QaState(String question,Map<String,String> answers,List<Vote> observations,String activeAnswer,
        Instant activeUntil,boolean disabled,long version,Instant updatedAt) {
    public record Vote(UUID sessionId,UUID runId,String answerHash,Instant at) {}
    public record Item(String id,QaState state) {}
    public record View(String id,String question,String answer,String status,int support,int sessions,
            double answerShare,Instant expiresAt,long version,Instant updatedAt) {}
    public QaState {
        answers=Map.copyOf(answers);observations=List.copyOf(observations);
        Map<String,String> savedAnswers=answers;
        if(question==null || question.isBlank() || question.length()>500 || answers.size()>256 || observations.size()>256 || version<0 || updatedAt==null
                || answers.entrySet().stream().anyMatch(e->!e.getKey().matches("[a-f0-9]{64}") || e.getValue().isBlank() || e.getValue().length()>4000)
                || observations.stream().anyMatch(v->v.sessionId()==null || v.runId()==null || v.at()==null || !savedAnswers.containsKey(v.answerHash()))
                || observations.stream().map(Vote::sessionId).distinct().count()!=observations.size()
                || activeAnswer!=null && (!answers.containsKey(activeAnswer) || activeUntil==null))throw new IllegalArgumentException("Invalid Q&A state");
    }
    public static QaState empty(String question,Instant now){return new QaState(question,Map.of(),List.of(),null,null,false,0,now);}
}
