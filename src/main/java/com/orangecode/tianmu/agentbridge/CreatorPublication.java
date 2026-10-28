package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.service.*;
import com.orangecode.tianmu.model.dto.video.CreateCommentRequest;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

/** Uses existing comment and bullet ingestion; one video lock and transaction for the whole confirmed batch. */
@Service
@ConditionalOnProperty(name="agent.bridge.enabled", havingValue="true")
public class CreatorPublication {
    public record Item(String id, String text, Long playbackMs) {}
    public record Batch(String videoId, String kind, List<Item> items) {}
    private final JdbcTemplate db;
    private final CommentService comments;
    private final BulletIngestionService bullets;
    public CreatorPublication(JdbcTemplate db, CommentService comments, BulletIngestionService bullets) {
        this.db = db; this.comments = comments; this.bullets = bullets;
    }
    @Transactional(rollbackFor=Exception.class)
    public void publish(BusinessAuthentication.Principal principal, Batch batch) {
        if (batch == null || batch.videoId() == null || !batch.videoId().matches("[1-9][0-9]{0,18}")
                || batch.kind() == null || !Set.of("COMMENT","DANMAKU").contains(batch.kind())
                || batch.items() == null || batch.items().isEmpty() || batch.items().size() > 10)
            throw new BridgeException("INVALID_DRAFT", 400);
        long video = positive(batch.videoId()), user = positive(principal.userId());
        var rows = db.queryForList("SELECT duration FROM video WHERE video_id=? AND user_id=? AND is_delete=0 FOR UPDATE", video, user);
        if (rows.size() != 1) throw new BridgeException("FORBIDDEN", 403);
        double duration = ((Number)rows.get(0).get("duration")).doubleValue();
        Set<String> texts = new HashSet<>(), ids = new HashSet<>();
        for (Item item : batch.items()) {
            if (item == null || item.id() == null || !ids.add(item.id()) || item.text() == null || item.text().isBlank()
                    || !item.text().equals(item.text().strip()) || item.text().codePoints().anyMatch(Character::isISOControl)
                    || !texts.add(item.text().replaceAll("\\s+", "").toLowerCase(Locale.ROOT))
                    || item.text().length() > (batch.kind().equals("COMMENT") ? 500 : 80)
                    || batch.kind().equals("COMMENT") && item.playbackMs() != null
                    || batch.kind().equals("DANMAKU") && (item.playbackMs() == null || item.playbackMs() < 0
                        || !Double.isFinite(duration) || item.playbackMs() >= duration * 1000))
                throw new BridgeException("INVALID_DRAFT", 400);
            long id = positive(item.id());
            if (batch.kind().equals("DANMAKU")) {
                SendBulletRequest event = new SendBulletRequest();
                event.setBulletId(id); event.setVideoId(video); event.setUserId(user); event.setContent(item.text());
                event.setPlaybackTime(item.playbackMs() / 1000.0); event.setColor("#FFFFFF"); event.setCreateTime(new Date());
                bullets.persist(event);
            } else {
                var previous = db.queryForList("SELECT video_id,user_id,content,parent_comment_id FROM comment WHERE comment_id=?", id);
                if (!previous.isEmpty()) {
                    var saved = previous.get(0);
                    if (((Number)saved.get("video_id")).longValue() != video || ((Number)saved.get("user_id")).longValue() != user
                            || !Objects.equals(saved.get("content"), item.text())
                            || saved.get("parent_comment_id") != null && ((Number)saved.get("parent_comment_id")).longValue() != 0)
                        throw new BridgeException("DRAFT_CONFLICT", 409);
                    continue;
                }
                CreateCommentRequest request = new CreateCommentRequest();
                request.setVideoId(video); request.setUserId(user); request.setContent(item.text());
                comments.createAuthorComment(request, id);
            }
        }
    }
    private static long positive(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; } catch (Exception ignored) {}
        throw new BridgeException("INVALID_DRAFT",400);
    }
}
