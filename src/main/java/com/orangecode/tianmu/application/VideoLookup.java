package com.orangecode.tianmu.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orangecode.tianmu.mapper.VideoMapper;
import com.orangecode.tianmu.model.entity.Video;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.ThrowUtils;
import org.springframework.stereotype.Service;

/** Read-only existence boundary: interaction services do not depend on video orchestration. */
@Service
public final class VideoLookup {
    private final VideoMapper videos;
    public VideoLookup(VideoMapper videos) { this.videos = videos; }
    public void requireExists(Long id) {
        ThrowUtils.throwIf(!videos.exists(new LambdaQueryWrapper<Video>().eq(Video::getVideoId, id)), ErrorCode.VIDEO_NOT_FOUND_ERROR);
    }

    /** Public danmaku writes must not target deleted, unpublished or withdrawn videos. */
    public void requirePublished(Long id) {
        ThrowUtils.throwIf(!videos.exists(new LambdaQueryWrapper<Video>().eq(Video::getVideoId, id)
                .eq(Video::getIsDelete, 0).eq(Video::getStatus, 2)), ErrorCode.VIDEO_NOT_FOUND_ERROR);
    }
}
