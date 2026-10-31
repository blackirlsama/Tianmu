package com.orangecode.tianmu.application;

import com.orangecode.tianmu.common.ErrorCode;
import com.orangecode.tianmu.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;

/** The reward date and balance change share one atomic database update. */
@Service
public class DailyOnlineReward {
    private final JdbcTemplate db;
    private final Clock clock;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    public DailyOnlineReward(JdbcTemplate db) { this(db, Clock.systemUTC()); }

    DailyOnlineReward(JdbcTemplate db, Clock clock) { this.db = db; this.clock = clock; }

    public record Result(LocalDate day, int coinCount, boolean rewarded) {}

    @Transactional(rollbackFor = Exception.class)
    public Result visit(long userId) {
        if (userId <= 0) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        int changed = db.update("""
                UPDATE user_stats SET coin_count=COALESCE(coin_count,0)+1, last_online_reward_date=?
                WHERE user_id=? AND is_delete=0
                  AND (last_online_reward_date IS NULL OR last_online_reward_date < ?)
                """, today, userId, today);
        var balances = db.queryForList("SELECT coin_count FROM user_stats WHERE user_id=? AND is_delete=0", Integer.class, userId);
        if (balances.size() != 1 || balances.get(0) == null) throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户硬币账户不存在或异常");
        return new Result(today, balances.get(0), changed == 1);
    }
}
