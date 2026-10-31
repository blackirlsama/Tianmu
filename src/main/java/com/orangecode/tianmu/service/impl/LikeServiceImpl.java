package com.orangecode.tianmu.service.impl;

import java.util.concurrent.TimeUnit;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.LikeMapper;
import com.orangecode.tianmu.model.dto.video.CancelVideoActionRequest;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.Like;
import com.orangecode.tianmu.model.entity.User;
import com.orangecode.tianmu.model.entity.VideoStats;
import com.orangecode.tianmu.service.LikeService;
import com.orangecode.tianmu.service.UserService;
import com.orangecode.tianmu.application.CurrentUser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.Objects;
import com.orangecode.tianmu.service.VideoStatsService;
import com.orangecode.tianmu.utils.CounterUtil;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
public class LikeServiceImpl extends ServiceImpl<LikeMapper, Like>
    implements LikeService {


    @Resource
    private com.orangecode.tianmu.application.VideoLookup videoLookup;

    @Resource
    private VideoStatsService videoStatsService;

    @Resource
    private UserService userService;

    @Resource
    private CounterUtil counterUtil;

    @Resource
    private CurrentUser currentUser;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long likeVideo(VideoActionRequest videoActionRequest) {

        ThrowUtils.throwIf(videoActionRequest == null || videoActionRequest.getVideoId() == null
                || videoActionRequest.getVideoId() <= 0, ErrorCode.PARAMS_ERROR);
        long userId = currentUser.requireMatchingUser(videoActionRequest.getUserId());

         //检测点赞频率是否过快
        crawlerLikeDetect(userId, videoActionRequest.getVideoId());

         //校验判断视频是否存在
        videoLookup.requireExists(videoActionRequest.getVideoId());


        // 校验判断用户是否存在
        ThrowUtils.throwIf(!userService.lambdaQuery().eq(User::getUserId, userId).exists(), ErrorCode.USER_NOT_EXISTS);

        // 查询是否已经点赞
        ThrowUtils.throwIf(this.lambdaQuery().eq(Like::getVideoId, videoActionRequest.getVideoId()).eq(Like::getUserId, userId).exists(), ErrorCode.VIDEO_LIKED_ERROR);


        // 保存点赞记录
        Like likeVideo = new Like();
        likeVideo.setVideoId(videoActionRequest.getVideoId());
        likeVideo.setUserId(userId);
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        likeVideo.setLikeId(snowflake.nextId());
        boolean save = this.save(likeVideo);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR);

        // 视频点赞数+1
        boolean updated = videoStatsService.lambdaUpdate().setSql("like_count = like_count + 1").eq(VideoStats::getVideoId, likeVideo.getVideoId()).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return likeVideo.getLikeId();



    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancelLikeVideo(CancelVideoActionRequest cancelVideoActionRequest) {
        long userId = currentUser.requireUserId();
        ThrowUtils.throwIf(cancelVideoActionRequest == null || cancelVideoActionRequest.getId() == null
                || cancelVideoActionRequest.getId() <= 0 || cancelVideoActionRequest.getVideoId() == null
                || cancelVideoActionRequest.getVideoId() <= 0, ErrorCode.PARAMS_ERROR);
        Like record = getById(cancelVideoActionRequest.getId());
        ThrowUtils.throwIf(record == null, ErrorCode.VIDEO_LIKED_NOT_EXISTS);
        ThrowUtils.throwIf(!Objects.equals(record.getUserId(), userId), ErrorCode.NO_AUTH_ERROR);
        ThrowUtils.throwIf(!Objects.equals(record.getVideoId(), cancelVideoActionRequest.getVideoId()), ErrorCode.PARAMS_ERROR);

        // 删除点赞记录
        boolean remove = this.remove(new LambdaQueryWrapper<Like>()
                .eq(Like::getLikeId, record.getLikeId()).eq(Like::getUserId, userId)
                .eq(Like::getVideoId, record.getVideoId()));
        // Only the request that actually deletes a row may decrement statistics.
        ThrowUtils.throwIf(!remove, ErrorCode.VIDEO_LIKED_NOT_EXISTS);

        // 视频点赞数 -1
        boolean updated = videoStatsService.lambdaUpdate().setSql("like_count = like_count - 1")
                .eq(VideoStats::getVideoId, record.getVideoId()).gt(VideoStats::getLikeCount, 0).update();
        ThrowUtils.throwIf(!updated, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");

        return true;
    }


    private void crawlerLikeDetect(long userId, long videoId) {
        // 调用多少次时告警
        final int WARN_COUNT = 2;
        // 拼接访问 key
        String key = String.format("like:%s:%s", userId, videoId);
        // 统计一分钟内访问次数，80 秒过期
        long count = counterUtil.incrAndGetCounter(key, 1, TimeUnit.MINUTES, 80);

        // 是否告警
        ThrowUtils.throwIf(count > WARN_COUNT, ErrorCode.ACCESS_TOO_FREQUENTLY);
    }

}




