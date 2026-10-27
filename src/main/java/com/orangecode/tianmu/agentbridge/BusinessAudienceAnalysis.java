package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** Both existing readers participate in the same short repeatable-read transaction and connection. */
public final class BusinessAudienceAnalysis {
    public record Snapshot(BusinessDanmakuAnalysis.Dataset danmaku,BusinessCommentAnalysis.Dataset comments,long uniqueAudience) {}
    private final BusinessDanmakuAnalysis danmaku;
    private final BusinessCommentAnalysis comments;
    private final TransactionTemplate tx;
    private final JdbcTemplate db;
    private final BridgeProperties config;
    public BusinessAudienceAnalysis(JdbcTemplate db,BridgeProperties config,BusinessDanmakuAnalysis danmaku,BusinessCommentAnalysis comments) {
        this.danmaku=danmaku;this.comments=comments;this.db=db;this.config=config;db.setQueryTimeout(config.queryTimeoutSeconds());
        tx=new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        tx.setReadOnly(true);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setTimeout(config.queryTimeoutSeconds());
    }
    public Snapshot query(BusinessAuthentication.Principal principal,long videoId,LocalDateTime start,LocalDateTime end) {
        BusinessDanmakuAnalysis.validateRange(videoId,start,end);
        return tx.execute(status->{
            var d=danmaku.query(principal,videoId,start,end);var c=comments.query(principal,videoId,start,end);
            String[] bullets=bounds("bullet",start,end),replies=bounds("comment",start,end);
            Long people=db.queryForObject("SELECT COUNT(DISTINCT user_id) FROM (SELECT user_id FROM bullet WHERE video_id=? AND create_time>=? AND create_time<? UNION ALL SELECT user_id FROM comment WHERE video_id=? AND create_time>=? AND create_time<?) audience WHERE user_id>0 AND user_id<>?",
                    Long.class,videoId,bullets[0],bullets[1],videoId,replies[0],replies[1],Long.parseLong(principal.userId()));
            return new Snapshot(d,c,Objects.requireNonNull(people));
        });
    }
    private String[] bounds(String table,LocalDateTime start,LocalDateTime end){
        String type=db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='create_time'",String.class,table);
        if(!Set.of("timestamp","datetime").contains(Objects.toString(type,"")))throw new BridgeException("UNSUPPORTED_TIME_COLUMN",503);
        ZoneId storage="timestamp".equals(type)?ZoneOffset.UTC:ZoneId.of(config.datetimeStorageZone());
        return new String[]{BusinessDanmakuAnalysis.wall(start,storage),BusinessDanmakuAnalysis.wall(end,storage)};
    }
}
