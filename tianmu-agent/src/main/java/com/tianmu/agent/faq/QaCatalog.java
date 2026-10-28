package com.tianmu.agent.faq;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

@Service
@EnableConfigurationProperties(QaPolicy.class)
public final class QaCatalog implements RunCompletionListener {
    private static final java.util.regex.Pattern DYNAMIC_QUESTION=java.util.regex.Pattern.compile("\\p{Nd}|今天|昨天|最近|实时|当前|过去|刚才|上面|前面|继续|我的|这(?:个|段|条|些)|那个|统计|多少|报告|复盘|数据",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern VOLATILE_ANSWER=java.util.regex.Pattern.compile("\\p{Nd}|https?://|/api/|[%％]|今天|昨天|目前|当前|截至|截止|这个视频|这段视频|不确定|无法确认|服务不可用|请求失败|共有|总共|累计|新增|增长|下降|上涨|下跌|百分之|条弹幕|条评论|次播放|粉丝数|点赞数",java.util.regex.Pattern.CASE_INSENSITIVE);
    private final QaStore store;private final QaPolicy policy;private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public QaCatalog(QaStore store,QaPolicy policy){this(store,policy,Clock.systemUTC());}
    public QaCatalog(QaStore store,QaPolicy policy,Clock clock){this.store=store;this.policy=policy;this.clock=clock;}
    public QaPolicy policy(){return policy;}
    public void completed(RunState run,Snapshot snapshot){
        if (run.pageContext()!=null) return;
        if(!policy.enabled() || snapshot.status()!=Status.SUCCEEDED || snapshot.stopReason()!=StopReason.ANSWER
                || snapshot.task()==null || !snapshot.task().specification().type().equals("conversation") || snapshot.turnSeq()!=1
                || snapshot.answer()==null || !snapshot.answer().evidenceIds().isEmpty() || !run.execution().evidence().snapshot().isEmpty())return;
        observe(run.owner,snapshot.sessionId(),snapshot.runId(),run.message,snapshot.answer().text(),snapshot.createdAt());
    }
    public void observe(AccessScope scope,UUID session,UUID run,String question,String answer,Instant observedAt){
        if(!policy.enabled() || session==null || run==null || observedAt==null || !eligible(question,answer))return;
        Instant now=clock.instant(),cutoff=now.minus(Duration.ofDays(policy.windowDays()));
        if(observedAt.isBefore(cutoff) || observedAt.isAfter(now.plusSeconds(5)))return;
        String normalized=normalize(question),questionKey=hash(normalized),text=answer.strip().replace("\r\n","\n"),answerKey=hash(text);
        boolean[] changed={false};
        QaState state=store.update(owner(scope),questionKey,QaState.empty(normalized,now),old->{
            if(old.disabled())return old;
            List<QaState.Vote> votes=new ArrayList<>(old.observations().stream().filter(v->!v.at().isBefore(cutoff)).toList());
            if(votes.stream().anyMatch(v->v.sessionId().equals(session)))return old;
            changed[0]=true;
            votes.add(new QaState.Vote(session,run,answerKey,observedAt));votes.sort(Comparator.comparing(QaState.Vote::at));
            if(votes.size()>policy.maximumObservations())votes=new ArrayList<>(votes.subList(votes.size()-policy.maximumObservations(),votes.size()));
            Map<String,String> answers=new HashMap<>(old.answers());answers.put(answerKey,text);
            Set<String> retained=new HashSet<>();votes.forEach(v->retained.add(v.answerHash()));answers.keySet().retainAll(retained);
            Map<String,Long> counts=counts(votes);String winner=winner(counts);long support=counts.getOrDefault(winner,0L);
            boolean admitted=support>=policy.minimumSessions() && (double)support/votes.size()>=policy.minimumAnswerShare();
            return new QaState(old.question(),answers,votes,admitted?winner:null,admitted?now.plus(Duration.ofDays(policy.validDays())):null,
                    false,old.version()+1,now);
        });
        if(changed[0] && state!=null)org.slf4j.LoggerFactory.getLogger(getClass()).info("qa_admission question={} version={} active={}",questionKey,state.version(),state.activeAnswer()!=null);
    }
    public Optional<FaqAnswer> find(Input input){
        if (input.memory().environment()!=null) return Optional.empty();
        if(!policy.enabled() || !staticQuestion(input.query()))return Optional.empty();
        QaState state=store.get(owner(input.scope()),hash(normalize(input.query())));
        if(state==null || state.disabled() || state.activeAnswer()==null || !clock.instant().isBefore(state.activeUntil()))return Optional.empty();
        var votes=current(state);long support=counts(votes).getOrDefault(state.activeAnswer(),0L);
        if(support<policy.minimumSessions() || votes.isEmpty() || (double)support/votes.size()<policy.minimumAnswerShare())return Optional.empty();
        String answer=state.answers().get(state.activeAnswer());if(!eligible(input.query(),answer))return Optional.empty();
        return Optional.of(new FaqAnswer("qa-"+hash(normalize(input.query())).substring(0,32),Long.toString(state.version()),answer));
    }
    public List<QaState.View> list(AccessScope scope){return store.list(owner(scope),100).stream().map(i->view(i.id(),i.state())).toList();}
    public QaState.View change(AccessScope scope,String id,long expectedVersion,boolean reset){
        if(id==null || !id.matches("[a-f0-9]{64}"))throw new AgentException("INVALID_REQUEST","Invalid Q&A identifier");
        QaState state=store.update(owner(scope),id,null,old->{
            if(old.version()!=expectedVersion)throw new AgentException("QA_CONFLICT","Q&A changed; refresh before updating");
            return reset?new QaState(old.question(),Map.of(),List.of(),null,null,false,old.version()+1,clock.instant())
                    :new QaState(old.question(),old.answers(),old.observations(),null,null,true,old.version()+1,clock.instant());
        });
        if(state==null)throw new AgentException("QA_NOT_FOUND","Q&A was not found");return view(id,state);
    }
    private QaState.View view(String id,QaState state){
        List<QaState.Vote> votes=current(state);Map<String,Long> counts=counts(votes);String best=winner(counts);
        long support=counts.getOrDefault(best,0L);boolean active=policy.enabled() && !state.disabled() && state.activeAnswer()!=null && clock.instant().isBefore(state.activeUntil())
                && counts.getOrDefault(state.activeAnswer(),0L)>=policy.minimumSessions() && !votes.isEmpty()
                && (double)counts.getOrDefault(state.activeAnswer(),0L)/votes.size()>=policy.minimumAnswerShare();
        String status=state.disabled()?"DISABLED":!policy.enabled()?"PAUSED":active?"ACTIVE":state.activeAnswer()!=null?"EXPIRED":"CANDIDATE";
        return new QaState.View(id,state.question(),state.answers().getOrDefault(best,""),status,(int)support,votes.size(),votes.isEmpty()?0:(double)support/votes.size(),state.activeUntil(),state.version(),state.updatedAt());
    }
    private List<QaState.Vote> current(QaState state){Instant since=clock.instant().minus(Duration.ofDays(policy.windowDays()));return state.observations().stream().filter(v->!v.at().isBefore(since)).toList();}
    private Map<String,Long> counts(List<QaState.Vote> votes){Map<String,Long> result=new TreeMap<>();votes.forEach(v->result.merge(v.answerHash(),1L,Long::sum));return result;}
    private String winner(Map<String,Long> counts){return counts.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).map(Map.Entry::getKey).findFirst().orElse("");}
    private static boolean eligible(String question,String answer){return staticQuestion(question) && answer!=null && answer.strip().length()>=12 && answer.length()<=4000
            && !VOLATILE_ANSWER.matcher(answer).find();}
    private static boolean staticQuestion(String text){
        if(text==null || text.strip().length()<4 || text.length()>500)return false;
        return !DYNAMIC_QUESTION.matcher(text).find();
    }
    static String normalize(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).strip().replaceAll("\\s+"," ").replaceAll("[?!。]+$","").toLowerCase(Locale.ROOT);}
    private static String owner(AccessScope scope){return hash(scope.tenantId().length()+":"+scope.tenantId()+scope.userId().length()+":"+scope.userId());}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
