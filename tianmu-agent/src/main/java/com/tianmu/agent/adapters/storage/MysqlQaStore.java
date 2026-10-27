package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.faq.*;
import com.tianmu.agent.core.AgentException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.UnaryOperator;

@Component
@DependsOn("agentMigrations")
@ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
public final class MysqlQaStore implements QaStore {
    private final JdbcTemplate db;private final TransactionTemplate tx;private final ObjectMapper json;
    public MysqlQaStore(DataSource source,ObjectMapper json){db=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.json=json;}
    public QaState get(String owner,String question){
        try{var rows=db.queryForList("SELECT state_json FROM agent_qa_catalog WHERE owner_key=? AND question_key=?",String.class,owner,question);return rows.isEmpty()?null:decode(rows.get(0));}
        catch(org.springframework.dao.DataAccessException e){throw unavailable();}
    }
    public List<QaState.Item> list(String owner,int limit){
        try{return db.query("SELECT question_key,state_json FROM agent_qa_catalog WHERE owner_key=? ORDER BY updated_at DESC LIMIT ?",
                (r,n)->new QaState.Item(r.getString(1),decode(r.getString(2))),owner,limit);}
        catch(org.springframework.dao.DataAccessException e){throw unavailable();}
    }
    public QaState update(String owner,String question,QaState initial,UnaryOperator<QaState> change){
        try{return tx.execute(status->{
            if(initial!=null)db.update("INSERT INTO agent_qa_catalog(owner_key,question_key,state_json,updated_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE owner_key=owner_key",
                    owner,question,encode(initial),Timestamp.from(initial.updatedAt()));
            var rows=db.queryForList("SELECT state_json FROM agent_qa_catalog WHERE owner_key=? AND question_key=? FOR UPDATE",String.class,owner,question);
            if(rows.isEmpty())return null;QaState previous=decode(rows.get(0)),next=change.apply(previous);
            if(next!=previous)db.update("UPDATE agent_qa_catalog SET state_json=?,updated_at=? WHERE owner_key=? AND question_key=?",encode(next),Timestamp.from(next.updatedAt()),owner,question);
            return next;
        });}catch(org.springframework.dao.DataAccessException e){throw unavailable();}
    }
    private QaState decode(String body){try{if(body.length()>1048576)throw unavailable();return json.readValue(body,QaState.class);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw unavailable();}}
    private String encode(QaState value){try{String body=json.writeValueAsString(value);if(body.length()>1048576)throw unavailable();return body;}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw unavailable();}}
    private AgentException unavailable(){return new AgentException("QA_UNAVAILABLE","Q&A catalog is unavailable");}
}
