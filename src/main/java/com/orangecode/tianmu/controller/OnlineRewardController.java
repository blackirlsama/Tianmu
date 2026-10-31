package com.orangecode.tianmu.controller;

import com.orangecode.tianmu.application.CurrentUser;
import com.orangecode.tianmu.application.DailyOnlineReward;
import com.orangecode.tianmu.common.BaseResponse;
import com.orangecode.tianmu.common.ResultUtils;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class OnlineRewardController {
    private final CurrentUser currentUser;
    private final DailyOnlineReward rewards;

    public OnlineRewardController(CurrentUser currentUser, DailyOnlineReward rewards) {
        this.currentUser = currentUser; this.rewards = rewards;
    }

    @PostMapping("/online")
    public BaseResponse<DailyOnlineReward.Result> online() {
        return ResultUtils.success(rewards.visit(currentUser.requireUserId()));
    }
}
