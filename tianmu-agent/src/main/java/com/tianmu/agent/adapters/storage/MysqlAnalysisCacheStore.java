package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.runtime.store.AnalysisCacheStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Timestamp;

@Component
@DependsOn("agentMigrations")
@ConditionalOnProperty(name="agent.storage.mode",havingValue="mysql")
public final class MysqlAnalysisCacheStore implements AnalysisCacheStore {
    private final JdbcTemplate db;
    private volatile long retryAfter;
    public MysqlAnalysisCacheStore(DataSource source){db=new JdbcTemplate(source);}
    public Entry get(String key){
        if(retryAfter!=0 && System.nanoTime()-retryAfter<0)return null;
        try {
            var rows=db.query("SELECT progress_rank,payload,expires_at FROM agent_report_cache WHERE cache_key=? AND expires_at>UTC_TIMESTAMP(6)",
                    (r,n)->new Entry(r.getLong(1),r.getString(2),r.getTimestamp(3).toInstant()),key);
            return rows.isEmpty()?null:rows.get(0);
        } catch(org.springframework.dao.DataAccessException e){failure();return null;}
    }
    public void put(String key,Entry entry){
        if(retryAfter!=0 && System.nanoTime()-retryAfter<0)return;
        try {
            // An expired checkpoint may be replaced; otherwise slower writers cannot move progress backwards.
            db.update("INSERT INTO agent_report_cache(cache_key,progress_rank,payload,expires_at) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE "
                    +"payload=IF(expires_at<=UTC_TIMESTAMP(6) OR progress_rank<=VALUES(progress_rank),VALUES(payload),payload),"
                    +"progress_rank=IF(expires_at<=UTC_TIMESTAMP(6),VALUES(progress_rank),GREATEST(progress_rank,VALUES(progress_rank))),"
                    +"expires_at=IF(progress_rank<=VALUES(progress_rank) OR expires_at<=UTC_TIMESTAMP(6),VALUES(expires_at),expires_at)",key,entry.rank(),entry.json(),Timestamp.from(entry.expiresAt()));
        } catch(org.springframework.dao.DataAccessException e){failure();}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    public void cleanup(){
        if(retryAfter!=0 && System.nanoTime()-retryAfter<0)return;
        try{
            db.update("DELETE FROM agent_report_cache WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 1000");
            db.update("DELETE FROM agent_report_leases WHERE valid_until<=UTC_TIMESTAMP(6) LIMIT 1000");
        }catch(org.springframework.dao.DataAccessException e){failure();}
    }
    private void failure(){retryAfter=System.nanoTime()+java.time.Duration.ofSeconds(2).toNanos();org.slf4j.LoggerFactory.getLogger(getClass()).warn("report_cache_storage_unavailable");}
    public boolean acquire(String key,java.util.UUID runId){
        if(retryAfter!=0 && System.nanoTime()-retryAfter<0)return true;
        try{
            db.update("INSERT INTO agent_report_leases(cache_key,run_id,valid_until) VALUES(?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 120 SECOND)) ON DUPLICATE KEY UPDATE "
                    +"run_id=IF(valid_until<=UTC_TIMESTAMP(6) OR run_id=VALUES(run_id),VALUES(run_id),run_id),"
                    +"valid_until=IF(run_id=VALUES(run_id),VALUES(valid_until),valid_until)",key,runId.toString());
            String holder=db.queryForObject("SELECT run_id FROM agent_report_leases WHERE cache_key=? AND valid_until>UTC_TIMESTAMP(6)",String.class,key);
            return runId.toString().equals(holder);
        }catch(org.springframework.dao.DataAccessException e){failure();return true;}
    }
    public void release(java.util.UUID runId){
        if(retryAfter!=0 && System.nanoTime()-retryAfter<0)return;
        try{db.update("DELETE FROM agent_report_leases WHERE run_id=?",runId.toString());}
        catch(org.springframework.dao.DataAccessException e){failure();}
    }
}
