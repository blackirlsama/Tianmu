package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.core.AgentException;
import java.time.Instant;
import java.util.ArrayList;

/** Provider output is not evidence until it is anchored to the authorized sample. */
final class VideoFactsValidator {
    static void source(VideoContent.Source source, long videoId) {
        if (source == null || source.videoId() != videoId || source.durationMs() <= 0 || source.durationMs() > 3_600_000
                || source.sourceVersion() == null || !source.sourceVersion().matches("[a-f0-9]{64}"))
            throw new AgentException("INVALID_EVIDENCE", "Invalid video source");
    }

    static void media(VideoContent.Source source, VideoContent.Media media, long from, long to) {
        if (media == null || !source.equals(media.source()) || media.startMs() != from || media.endMs() != to
                || media.frames() == null || media.frames().isEmpty() || media.frames().size() > 12)
            throw new AgentException("INVALID_EVIDENCE", "Video sample does not match authorized source and range");
        for (var frame : media.frames()) {
            if (frame == null || frame.atMs() < from || frame.atMs() >= to || frame.jpegBase64() == null || frame.jpegBase64().length() > 140_000)
                throw new AgentException("INVALID_EVIDENCE", "Frame outside requested range");
        }
    }

    static VideoContent.Facts facts(VideoContent.Media media, JsonNode response, String transcript, String model) {
        try {
            if (response == null || !response.path("summary").isTextual() || !response.path("moments").isArray()) throw new IllegalArgumentException();
            var moments = new ArrayList<VideoContent.Moment>();
            for (JsonNode moment : response.path("moments")) {
                if (!moment.path("atMs").isIntegralNumber() || !moment.path("atMs").canConvertToLong() || !moment.path("observation").isTextual())
                    throw new IllegalArgumentException();
                moments.add(new VideoContent.Moment(moment.path("atMs").longValue(), moment.path("observation").textValue()));
            }
            return new VideoContent.Facts("video-content-v1", media.source().videoId(), media.source().sourceVersion(), media.startMs(), media.endMs(),
                    response.path("summary").textValue(), moments, transcript, transcript.isBlank() ? "SAMPLED_FRAMES" : "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT",
                    media.frames().stream().map(VideoContent.Frame::atMs).toList(), model, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new AgentException("INVALID_VIDEO_ANALYSIS", "Vision response must cite provided frame timestamps and bounded text");
        }
    }
}
