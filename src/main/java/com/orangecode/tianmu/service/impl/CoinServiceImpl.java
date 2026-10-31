package com.orangecode.tianmu.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.application.CurrentUser;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.exception.ThrowUtils;
import com.orangecode.tianmu.mapper.CoinMapper;
import com.orangecode.tianmu.model.dto.video.VideoActionRequest;
import com.orangecode.tianmu.model.entity.*;
import com.orangecode.tianmu.service.*;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@SuppressWarnings("all")
public class CoinServiceImpl extends ServiceImpl<CoinMapper, Coin> implements CoinService {


    @Resource
    private VideoStatsService videoStatsService;


    @Resource
    private UserService userService;

    @Resource
    private com.orangecode.tianmu.application.VideoLookup videoLookup;

    @Resource
    private UserStatsService userStatsService;

    @Resource
    private CurrentUser currentUser;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean coinVideo(VideoActionRequest videoActionRequest) {

        ThrowUtils.throwIf(videoActionRequest == null || videoActionRequest.getVideoId() == null
                || videoActionRequest.getVideoId() <= 0, ErrorCode.PARAMS_ERROR);
        long userId = currentUser.requireMatchingUser(videoActionRequest.getUserId());

        // 校验判断用户是否已经投币
        ThrowUtils.throwIf(this.lambdaQuery().eq(Coin::getVideoId, videoActionRequest.getVideoId()).eq(Coin::getUserId, userId).exists(), ErrorCode.VIDEO_COIN_ERROR);

        // 校验判断视频是否存在
        videoLookup.requireExists(videoActionRequest.getVideoId());

        // 校验判断用户是否存在
        User user = userService.lambdaQuery().eq(User::getUserId, userId).one();
        ThrowUtils.throwIf(user == null, ErrorCode.USER_NOT_EXISTS);

        // 获取用户详细信息
        UserStats userStats = userStatsService.getById(user.getUserId());

        // 校验判断用户硬币是否足够
        ThrowUtils.throwIf(userStats == null || userStats.getCoinCount() == null || userStats.getCoinCount() < 1, ErrorCode.USER_COIN_ERROR);

        // 保存投币记录
        Coin coin = new Coin();
        coin.setVideoId(videoActionRequest.getVideoId());
        coin.setUserId(userId);
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        coin.setCoinId(snowflake.nextId());
        boolean save = this.save(coin);
        ThrowUtils.throwIf(!save, ErrorCode.SYSTEM_ERROR);


        // 视频投币数 +1
        boolean updatedVideoStats = videoStatsService.lambdaUpdate().setSql("coin_count = coin_count + 1").eq(VideoStats::getVideoId, videoActionRequest.getVideoId()).update();
        ThrowUtils.throwIf(!updatedVideoStats, ErrorCode.SYSTEM_ERROR, "更新视频统计失败");


        // 用户硬币数 -1
        boolean updatedUserCoin = userStatsService.lambdaUpdate().setSql("coin_count = coin_count - 1")
                .eq(UserStats::getUserId, userId).gt(UserStats::getCoinCount, 0).update();
        ThrowUtils.throwIf(!updatedUserCoin, ErrorCode.USER_COIN_ERROR, "投币失败，硬币不足");

        return true;

    }
}




