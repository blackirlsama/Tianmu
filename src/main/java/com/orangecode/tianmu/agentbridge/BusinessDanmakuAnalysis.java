package com.orangecode.tianmu.agentbridge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Fixed SQL only. Exact aggregates and bounded, explicitly biased excerpts share a read snapshot. */
public final class BusinessDanmakuAnalysis {
    public record Query(long videoId, LocalDateTime start, LocalDateTime end) {}
    public record Bucket(int index, long count) {}
    public record Sample(String id, int bucket, String text, boolean truncated, Long playbackMs) {}
    public record Dataset(Query query, long totalMessages, long uniqueSenders, List<Bucket> buckets,
                          List<Sample> samples, Instant dataAsOf, String source, boolean demo, Audience audience) {}
    public record Interaction(String userId, long messages, int activeDays, long distinctTexts, Instant lastAt) {}
    public record Position(long startMs, long endMs, long count) {}
    public record TextGroup(String id, String text, long count, Long playbackMs, boolean truncated, List<Long> trend) {}
    public record Audience(List<Interaction> interactions, List<Position> playback, long unknownPlayback,
                           List<TextGroup> corpus, long corpusMessages, long totalMessages) {}
    private record Totals(long messages, long senders, Instant asOf) {}
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final BridgeProperties config;
    public BusinessDanmakuAnalysis(JdbcTemplate source, BridgeProperties config) {
        this.config = config; db = new JdbcTemplate(Objects.requireNonNull(source.getDataSource())); db.setQueryTimeout(config.queryTimeoutSeconds());
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.getDataSource()));
        tx.setReadOnly(true); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); tx.setTimeout(config.queryTimeoutSeconds());
    }
    public Dataset query(BusinessAuthentication.Principal principal, long videoId, LocalDateTime start, LocalDateTime end) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        if (videoId <= 0 || start == null || end == null || !start.isBefore(end) || start.getNano() != 0 || end.getNano() != 0
                || Duration.between(start, end).compareTo(Duration.ofDays(30)) > 0 || end.isAfter(now)
                || start.toLocalDate().isBefore(now.toLocalDate().minusDays(30))) throw new BridgeException("INVALID_ANALYSIS_RANGE", 400);
        return tx.execute(status -> {
            // This ownership convention is shared with existing metric authorization.
            Integer allowed = db.queryForObject("SELECT COUNT(*) FROM video WHERE video_id=? AND user_id=? AND is_delete=0", Integer.class, videoId, Long.parseLong(principal.userId()));
            if (allowed == null || allowed != 1) throw new BridgeException("FORBIDDEN", 403);
            String type = db.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='bullet' AND COLUMN_NAME='create_time'", String.class);
            if (!"timestamp".equals(type) && !"datetime".equals(type)) throw new BridgeException("UNSUPPORTED_TIME_COLUMN", 503);
            ZoneId storage = "timestamp".equals(type) ? ZoneOffset.UTC : ZoneId.of(config.datetimeStorageZone());
            String from = wall(start, storage), to = wall(end, storage);
            long seconds = Duration.between(start, end).getSeconds(), width = Math.max(1, (seconds + 11) / 12);
            String scope = " FROM bullet WHERE video_id=? AND create_time>=? AND create_time<?";
            var totals = db.queryForObject("SELECT COUNT(*) AS total,COUNT(DISTINCT CASE WHEN user_id>0 THEN user_id ELSE NULL END) AS senders,UTC_TIMESTAMP(6) AS as_of" + scope,
                    (rs, n) -> new Totals(rs.getLong("total"), rs.getLong("senders"), rs.getTimestamp("as_of", Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant()), videoId, from, to);
            List<Bucket> buckets = new ArrayList<>(); long[] counts = new long[12];
            db.query("SELECT FLOOR(TIMESTAMPDIFF(SECOND,?,create_time)/?) AS bin,COUNT(*) AS n" + scope + " GROUP BY bin", rs -> {
                int bin = rs.getInt("bin"); if (bin < 0 || bin >= 12) throw new BridgeException("INVALID_ANALYSIS_DATA", 503);
                counts[bin] = rs.getLong("n");
            }, from, width, videoId, from, to);
            List<Sample> samples = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                buckets.add(new Bucket(i, counts[i])); if (counts[i] == 0) continue;
                int bin = i;
                LocalDateTime left = start.plusSeconds(width * i), right = start.plusSeconds(Math.min(seconds, width * (i + 1)));
                samples.addAll(db.query("SELECT bullet_id,CASE WHEN playback_time>=0 AND playback_time<=3600 THEN ROUND(playback_time*1000) ELSE NULL END AS playback_ms,LEFT(COALESCE(content,''),160) AS excerpt,CHAR_LENGTH(COALESCE(content,''))>160 AS clipped" + scope + " ORDER BY create_time,bullet_id LIMIT 4",
                        (rs, n) -> new Sample(Long.toString(rs.getLong("bullet_id")), bin, redact(rs.getString("excerpt")), rs.getBoolean("clipped"), rs.getObject("playback_ms")==null?null:rs.getLong("playback_ms")), videoId, wall(left, storage), wall(right, storage)));
            }
            return new Dataset(new Query(videoId, start, end), totals.messages(), totals.senders(),
                    List.copyOf(buckets), List.copyOf(samples), totals.asOf(), "mysql:bullet:danmaku-retained-v1", false,
                    audience(videoId, from, to, storage, totals.messages(),width));
        });
    }
    private Audience audience(long videoId, String from, String to, ZoneId storage, long total,long width) {
        String scope = " FROM bullet WHERE video_id=? AND create_time>=? AND create_time<?";
        String date = "CONVERT_TZ(create_time,?, '+08:00')";
        String offset = storage.getRules().getOffset(Instant.now()).getId();
        if (offset.equals("Z")) offset = "+00:00";
        List<Interaction> fans = db.query("SELECT user_id,COUNT(*) n,COUNT(DISTINCT DATE(" + date + ")) days,COUNT(DISTINCT BINARY content) texts,MAX(create_time) last_at"
                + scope + " AND user_id IS NOT NULL AND user_id>0 GROUP BY user_id ORDER BY days DESC,texts DESC,n DESC,user_id LIMIT 20",
                (rs,n) -> new Interaction(Long.toString(rs.getLong("user_id")),rs.getLong("n"),rs.getInt("days"),rs.getLong("texts"),
                        rs.getTimestamp("last_at", Calendar.getInstance(TimeZone.getTimeZone(storage))).toInstant()),offset,videoId,from,to);
        Double duration=db.queryForObject("SELECT duration FROM video WHERE video_id=?",Double.class,videoId);
        double supported=duration!=null && Double.isFinite(duration) && duration>0?Math.min(3600,duration):0;
        List<Position> playback = db.query("SELECT FLOOR(playback_time/30) bin,COUNT(*) n" + scope
                + " AND playback_time>=0 AND playback_time<? GROUP BY bin ORDER BY bin", (rs,n) -> {
                    long left=rs.getLong("bin")*30_000;return new Position(left,Math.min(left+30_000,Math.max(left+1,Math.round(supported*1000))),rs.getLong("n"));
                },videoId,from,to,supported);
        long located=playback.stream().mapToLong(Position::count).sum();
        List<TextGroup> corpus = db.query("SELECT b.bullet_id,LEFT(COALESCE(b.content,''),160) excerpt,CHAR_LENGTH(COALESCE(b.content,''))>160 clipped,g.n,"
                + "CASE WHEN b.playback_time>=0 AND b.playback_time<? THEN ROUND(b.playback_time*1000) ELSE NULL END playback_ms FROM ("
                + "SELECT MIN(bullet_id) id,COUNT(*) n" + scope + " AND content IS NOT NULL AND TRIM(content)<>'' GROUP BY BINARY content ORDER BY n DESC,id LIMIT 120) g JOIN bullet b ON b.bullet_id=g.id ORDER BY g.n DESC,g.id",
                (rs,n) -> new TextGroup(Long.toString(rs.getLong("bullet_id")),redact(rs.getString("excerpt")),rs.getLong("n"),
                        rs.getObject("playback_ms")==null?null:rs.getLong("playback_ms"),rs.getBoolean("clipped"),List.of()),supported,videoId,from,to);
        if(!corpus.isEmpty()) {
            Map<String,long[]> trends=new HashMap<>();corpus.forEach(g->trends.put(g.id(),new long[12]));
            String parameters=String.join(",",Collections.nCopies(corpus.size(),"?"));
            List<Object> args=new ArrayList<>();args.add(from);args.add(width);corpus.forEach(g->args.add(Long.parseLong(g.id())));args.add(videoId);args.add(from);args.add(to);
            db.query("SELECT e.bullet_id id,FLOOR(TIMESTAMPDIFF(SECOND,?,b.create_time)/?) bin,COUNT(*) n FROM bullet e JOIN bullet b ON b.video_id=e.video_id AND BINARY b.content=BINARY e.content"
                    +" WHERE e.bullet_id IN ("+parameters+") AND b.video_id=? AND b.create_time>=? AND b.create_time<? GROUP BY e.bullet_id,bin",rs->{
                        String id=Long.toString(rs.getLong("id"));int bin=rs.getInt("bin");
                        if(bin<0 || bin>=12 || !trends.containsKey(id))throw new BridgeException("INVALID_ANALYSIS_DATA",503);
                        trends.get(id)[bin]=rs.getLong("n");
                    },args.toArray());
            corpus=corpus.stream().map(g->new TextGroup(g.id(),g.text(),g.count(),g.playbackMs(),g.truncated(),Arrays.stream(trends.get(g.id())).boxed().toList())).toList();
        }
        return new Audience(List.copyOf(fans),List.copyOf(playback),total-located,List.copyOf(corpus),corpus.stream().mapToLong(TextGroup::count).sum(),total);
    }
    private static String wall(LocalDateTime time, ZoneId storage) { return time.atZone(ZONE).withZoneSameInstant(storage).toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")); }
    private static String redact(String text) {
        return text.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[email]").replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[phone]");
    }
}
