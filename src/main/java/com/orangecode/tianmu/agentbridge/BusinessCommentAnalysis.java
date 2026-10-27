package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Full retained comments in one read snapshot. Ancestors provide context, never extra period activity. */
public final class BusinessCommentAnalysis {
    public record Comment(String id, String parentId, String userId, String nickname, String text,
                          Instant createdAt, boolean inPeriod, boolean author, boolean parentUnavailable) {}
    public record Dataset(String kind, BusinessDanmakuAnalysis.Query query, long totalComments,
                          long audienceComments, long uniqueAudience, List<Comment> comments,
                          Instant dataAsOf, String source) {}
    private static final int LIMIT = 5000;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BridgeProperties config;

    public BusinessCommentAnalysis(JdbcTemplate source, BridgeProperties config) {
        this.config = config;
        db = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
        db.setQueryTimeout(config.queryTimeoutSeconds());
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(config.queryTimeoutSeconds());
    }

    public Dataset query(BusinessAuthentication.Principal principal, long videoId, LocalDateTime start, LocalDateTime end) {
        BusinessDanmakuAnalysis.validateRange(videoId, start, end);
        return tx.execute(status -> {
            long owner = Long.parseLong(principal.userId());
            if (!Objects.equals(1, db.queryForObject("SELECT COUNT(*) FROM video WHERE video_id=? AND user_id=? AND is_delete=0",
                    Integer.class, videoId, owner))) throw new BridgeException("FORBIDDEN", 403);
            String type = db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='comment' AND COLUMN_NAME='create_time'", String.class);
            if (!Set.of("timestamp", "datetime").contains(Objects.toString(type, ""))) throw new BridgeException("UNSUPPORTED_TIME_COLUMN", 503);
            ZoneId storage = "timestamp".equals(type) ? ZoneOffset.UTC : ZoneId.of(config.datetimeStorageZone());
            String from = wall(start, storage), to = wall(end, storage);
            LinkedHashMap<String, Comment> all = new LinkedHashMap<>();
            load("c.video_id=? AND c.create_time>=? AND c.create_time<? ORDER BY c.create_time,c.comment_id LIMIT 5001",
                    new Object[]{videoId, from, to}, storage, owner, true).forEach(c -> all.put(c.id(), c));
            checkSize(all.size());
            long total = all.size();
            long audience = all.values().stream().filter(c -> !c.author()).count();
            long people = all.values().stream().filter(c -> !c.author() && c.userId() != null).map(Comment::userId).distinct().count();
            // Iterative bulk loading avoids one query per reply and preserves the direct-parent relationship.
            Set<String> attempted = new HashSet<>(all.keySet());
            int depth = 0;
            while (true) {
                Set<String> missing = new TreeSet<>();
                all.values().forEach(c -> { if (c.parentId() != null && !attempted.contains(c.parentId())) missing.add(c.parentId()); });
                if (missing.isEmpty()) break;
                if (++depth > 64) throw new BridgeException("COMMENT_CONTEXT_TOO_DEEP", 422);
                attempted.addAll(missing);
                List<Object> args = new ArrayList<>(); args.add(videoId); missing.forEach(id -> args.add(Long.parseLong(id)));
                load("c.video_id=? AND c.comment_id IN (" + String.join(",", Collections.nCopies(missing.size(), "?")) + ") ORDER BY c.create_time,c.comment_id",
                        args.toArray(), storage, owner, false).forEach(c -> all.put(c.id(), c));
                checkSize(all.size());
            }
            rejectCycles(all);
            List<Comment> comments = all.values().stream().map(c -> new Comment(c.id(), c.parentId(), c.userId(), c.nickname(), c.text(),
                    c.createdAt(), c.inPeriod(), c.author(), c.parentId() != null && !all.containsKey(c.parentId()))).toList();
            Instant asOf = db.queryForObject("SELECT UTC_TIMESTAMP(6)", (rs, n) -> rs.getTimestamp(1, Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant());
            return new Dataset("video-comments-v1", new BusinessDanmakuAnalysis.Query(videoId, start, end), total,
                    audience, people, comments, asOf, "mysql:comment:retained-v1");
        });
    }
    private List<Comment> load(String where, Object[] args, ZoneId storage, long owner, boolean inPeriod) {
        return db.query("SELECT c.comment_id,c.parent_comment_id,c.user_id,c.content,c.create_time,u.nickname FROM comment c LEFT JOIN user u ON u.user_id=c.user_id WHERE " + where,
                (rs, n) -> {
                    Long parent = rs.getObject("parent_comment_id") == null ? null : rs.getLong("parent_comment_id");
                    Long user = rs.getObject("user_id") == null ? null : rs.getLong("user_id");
                    return new Comment(Long.toString(rs.getLong("comment_id")), parent == null || parent <= 0 ? null : parent.toString(),
                            user == null || user <= 0 ? null : user.toString(), BusinessDanmakuAnalysis.redact(Objects.toString(rs.getString("nickname"), "")),
                            BusinessDanmakuAnalysis.redact(Objects.toString(rs.getString("content"), "")),
                            rs.getTimestamp("create_time", Calendar.getInstance(TimeZone.getTimeZone(storage))).toInstant(), inPeriod,
                            user != null && user == owner, false);
                }, args);
    }
    private static void checkSize(int size) { if (size > LIMIT) throw new BridgeException("FULL_ANALYSIS_TOO_LARGE", 422); }
    private static String wall(LocalDateTime time, ZoneId storage) {
        return time.atZone(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(storage).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    private static void rejectCycles(Map<String, Comment> comments) {
        Set<String> checked = new HashSet<>();
        for (String id : comments.keySet()) {
            Set<String> path = new HashSet<>();
            String current = id;
            while (current != null && comments.containsKey(current) && !checked.contains(current)) {
                if (!path.add(current)) throw new BridgeException("INVALID_COMMENT_CONTEXT", 422);
                current = comments.get(current).parentId();
            }
            checked.addAll(path);
        }
    }
}
