package com.orangecode.tianmu.websocket;

import cn.hutool.json.JSONUtil;
import com.orangecode.tianmu.constants.WebSocketConstant;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import io.netty.channel.Channel;
import io.netty.channel.group.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded pending batch and at most one timer per active room. */
@Component
public class RoomBatchBroadcaster implements AutoCloseable {
    private static final String PREFIX = "{\"type\":" + WebSocketConstant.ONLINE_BULLET_BATCH + ",\"data\":[";
    private static final int ENVELOPE_BYTES = PREFIX.getBytes(StandardCharsets.UTF_8).length + 2;
    private final ConcurrentMap<Long, Room> rooms = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Limits limits;
    private final AtomicBoolean closed = new AtomicBoolean();

    record Limits(long windowMs, int maxItems, int maxBytes) {
        Limits {
            // Keep the batching window below the existing one-second send confirmation wait.
            if (windowMs < 1 || windowMs > 500 || maxItems < 1 || maxItems > 1000 || maxBytes < 4096 || maxBytes > 65536)
                throw new IllegalArgumentException("Invalid danmaku batch limits");
        }
    }

    @Autowired
    public RoomBatchBroadcaster(@Value("${netty.danmaku-batch.window-ms:100}") long windowMs,
                                @Value("${netty.danmaku-batch.max-items:50}") int maxItems,
                                @Value("${netty.danmaku-batch.max-bytes:16384}") int maxBytes) {
        this(new Limits(windowMs, maxItems, maxBytes), newScheduler());
    }

    RoomBatchBroadcaster(Limits limits, ScheduledExecutorService scheduler) {
        this.limits = limits; this.scheduler = scheduler;
    }

    private static ScheduledExecutorService newScheduler() {
        var executor = new ScheduledThreadPoolExecutor(2, runnable -> {
            var thread = new Thread(runnable, "danmaku-room-flush"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static final class Room {
        final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        final List<String> pending = new ArrayList<>();
        int bytes = ENVELOPE_BYTES;
        long generation;
        ScheduledFuture<?> timer;
        boolean disposed;
    }

    public void join(long roomId, Channel channel) {
        rooms.compute(roomId, (id, previous) -> {
            if (closed.get()) throw new IllegalStateException("Room broadcaster is closed");
            Room room = previous == null ? new Room() : previous;
            synchronized (room) {
                room.channels.add(channel);
                online(room);
            }
            return room;
        });
    }

    public void leave(long roomId, Channel channel) {
        rooms.computeIfPresent(roomId, (id, room) -> {
            synchronized (room) {
                room.channels.remove(channel);
                if (room.channels.isEmpty()) {
                    discard(room); room.disposed = true; return null;
                }
                online(room); return room;
            }
        });
    }

    public void append(long roomId, OnlineBulletResponse item) {
        Room room = rooms.get(roomId);
        if (room == null || closed.get()) return; // Persistence is independent of live viewers.
        String json = JSONUtil.toJsonStr(item);
        int bytes = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + ENVELOPE_BYTES > limits.maxBytes()) throw new IllegalArgumentException("Danmaku exceeds batch byte limit");
        synchronized (room) {
            if (room.disposed || closed.get()) return;
            int separator = room.pending.isEmpty() ? 0 : 1;
            if (room.bytes + separator + bytes > limits.maxBytes()) flush(room);
            room.bytes += bytes + (room.pending.isEmpty() ? 0 : 1);
            room.pending.add(json);
            if (room.pending.size() >= limits.maxItems() || room.bytes >= limits.maxBytes()) {
                flush(room);
            } else if (room.timer == null) {
                long generation = ++room.generation;
                room.timer = scheduler.schedule(() -> {
                    synchronized (room) {
                        if (!room.disposed && room.generation == generation) flush(room);
                    }
                }, limits.windowMs(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /** Caller holds the room lock so timer/count/byte flushes preserve per-room append order. */
    private void flush(Room room) {
        if (room.pending.isEmpty()) return;
        String frame = PREFIX + String.join(",", room.pending) + "]}";
        discard(room);
        room.channels.writeAndFlush(new TextWebSocketFrame(frame));
    }

    private void discard(Room room) {
        ++room.generation;
        if (room.timer != null) room.timer.cancel(false);
        room.timer = null; room.pending.clear(); room.bytes = ENVELOPE_BYTES;
    }

    private void online(Room room) {
        room.channels.writeAndFlush(new TextWebSocketFrame("{\"type\":1,\"data\":" + room.channels.size() + "}"));
    }

    int roomCount() { return rooms.size(); }

    @Override @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Room room : rooms.values()) {
            synchronized (room) { flush(room); discard(room); room.disposed = true; }
            room.channels.close().awaitUninterruptibly();
        }
        rooms.clear(); scheduler.shutdownNow();
    }
}
