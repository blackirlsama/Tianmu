package com.orangecode.tianmu.agentbridge;

import io.minio.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Resolves only owned database references, never model-supplied media URLs. */
public final class BusinessVideoContent {
    public record Source(long videoId, String sourceVersion, long durationMs) {}
    public record Frame(long atMs, String jpegBase64) {}
    public record Media(Source source, long startMs, long endMs, List<Frame> frames, String audioBase64) {}
    private record Resolved(Source source, String object, long bytes) {}
    private final JdbcTemplate db;
    private final MinioClient minio;
    private final String bucket;
    private final Semaphore slots = new Semaphore(2);
    public BusinessVideoContent(JdbcTemplate db, MinioClient minio, String bucket) {
        this.db = db; this.minio = minio; this.bucket = bucket;
    }
    public Source source(BusinessAuthentication.Principal principal, long videoId) { return resolve(principal, videoId).source(); }
    private Resolved resolve(BusinessAuthentication.Principal principal, long videoId) {
        var rows = db.queryForList("SELECT file_url,duration FROM video WHERE video_id=? AND user_id=? AND is_delete=0", videoId, Long.parseLong(principal.userId()));
        if (rows.size() != 1) throw new BridgeException("FORBIDDEN", 403);
        if (minio == null) throw new BridgeException("VIDEO_STORAGE_UNAVAILABLE", 503);
        try {
            String path = URI.create(rows.get(0).get("file_url").toString()).getPath();
            String prefix = "/" + bucket + "/";
            if (!path.startsWith(prefix)) throw new BridgeException("VIDEO_SOURCE_UNSUPPORTED", 422);
            String object = path.substring(prefix.length());
            if (!object.matches("[A-Za-z0-9_-]{8,128}\\.(?i:mp4|webm|mov|avi)")) throw new BridgeException("VIDEO_SOURCE_UNSUPPORTED", 422);
            long duration = Math.round(((Number) rows.get(0).get("duration")).doubleValue() * 1000);
            if (duration < 1 || duration > 3_600_000) throw new BridgeException("VIDEO_DURATION_UNSUPPORTED", 422);
            var stat = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(object).build());
            if (stat.size() < 1 || stat.size() > 67_108_864) throw new BridgeException("VIDEO_TOO_LARGE", 422);
            String version = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((object + ":" + stat.etag() + ":" + stat.size() + ":" + duration).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new Resolved(new Source(videoId, version, duration), object, stat.size());
        } catch (BridgeException e) { throw e; }
        catch (Exception e) { throw new BridgeException("VIDEO_SOURCE_UNAVAILABLE", 503); }
    }
    public Media sample(BusinessAuthentication.Principal principal, long videoId, String version, long start, long end) {
        Resolved resolved = resolve(principal, videoId);
        Source source = resolved.source();
        if (!source.sourceVersion().equals(version)) throw new BridgeException("VIDEO_VERSION_CHANGED", 409);
        if (start < 0 || end <= start || end > source.durationMs()
                || (end - start > 120_000 && (start != 0 || end != source.durationMs()))) throw new BridgeException("INVALID_PLAYBACK_RANGE", 400);
        if (!slots.tryAcquire()) throw new BridgeException("VIDEO_PROCESSOR_BUSY", 503);
        Path temp = null;
        Instant deadline = Instant.now().plusSeconds(50);
        try {
            temp = Files.createTempDirectory("tianmu-video-");
            Path input = temp.resolve("input." + resolved.object().substring(resolved.object().lastIndexOf('.') + 1));
            try (var in = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(resolved.object()).build());
                 var out = Files.newOutputStream(input)) {
                byte[] buffer = new byte[65536]; long total = 0; int read;
                while ((read = in.read(buffer)) >= 0) {
                    check(deadline); total += read;
                    if (total > resolved.bytes()) throw new BridgeException("VIDEO_VERSION_CHANGED", 409);
                    out.write(buffer, 0, read);
                }
                if (total != resolved.bytes()) throw new BridgeException("VIDEO_VERSION_CHANGED", 409);
            }
            if (!resolve(principal, videoId).source().sourceVersion().equals(version)) throw new BridgeException("VIDEO_VERSION_CHANGED", 409);
            List<Frame> frames = new ArrayList<>();
            int count = (int)Math.min(12, Math.max(1, (end - start + 999) / 1000));
            for (int i = 0; i < count; i++) {
                long at = start + (end - start) * (2L * i + 1) / (2L * count);
                Path frame = temp.resolve("frame-" + i + ".jpg");
                process(List.of("ffmpeg", "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm,avi", "-threads", "1", "-ss", seconds(at),
                        "-i", input.toString(), "-map", "0:v:0", "-frames:v", "1", "-vf", "scale=640:360:force_original_aspect_ratio=decrease", "-q:v", "7", "-threads", "1", frame.toString()), deadline);
                if (!Files.exists(frame) || Files.size(frame) > 100_000 || Files.size(frame) < 1) throw new BridgeException("VIDEO_FRAME_UNAVAILABLE", 422);
                frames.add(new Frame(at, Base64.getEncoder().encodeToString(Files.readAllBytes(frame))));
            }
            String audio = "";
            if (end - start <= 120_000) {
                Path wav = temp.resolve("audio.wav");
                // Optional audio stream: silent clips are valid visual inputs.
                process(List.of("ffmpeg", "-nostdin", "-v", "error", "-protocol_whitelist", "file,pipe", "-format_whitelist", "mov,matroska,webm,avi", "-threads", "1", "-ss", seconds(start),
                        "-i", input.toString(), "-t", seconds(end - start), "-map", "0:a:0?", "-vn", "-ac", "1", "-ar", "16000", "-threads", "1", wav.toString()), deadline, true);
                if (Files.exists(wav) && Files.size(wav) > 44 && Files.size(wav) < 4_000_000)
                    audio = Base64.getEncoder().encodeToString(Files.readAllBytes(wav));
            }
            return new Media(source, start, end, List.copyOf(frames), audio);
        } catch (BridgeException e) { throw e; }
        catch (Exception e) { throw new BridgeException("VIDEO_EXTRACTION_UNAVAILABLE", 503); }
        finally {
            if (temp != null) try (var paths = Files.walk(temp)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
            } catch (Exception ignored) {}
            slots.release();
        }
    }
    private static String seconds(long millis) { return String.format(Locale.ROOT, "%.3f", millis / 1000.0); }
    private static void check(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || !Instant.now().isBefore(deadline)) throw new BridgeException("VIDEO_EXTRACTION_TIMEOUT", 504);
    }
    private static void process(List<String> args, Instant deadline) throws Exception { process(args, deadline, false); }
    private static void process(List<String> args, Instant deadline, boolean optional) throws Exception {
        check(deadline);
        Process process = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS))
                throw new BridgeException("VIDEO_EXTRACTION_TIMEOUT", 504);
            if (process.exitValue() != 0 && !optional) throw new BridgeException("VIDEO_DECODE_FAILED", 422);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
