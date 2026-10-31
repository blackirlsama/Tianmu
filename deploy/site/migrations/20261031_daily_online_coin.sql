-- Run once against the business database before deploying the daily online reward.
-- Existing users receive their first reward on their next authenticated visit.
ALTER TABLE user_stats ADD COLUMN last_online_reward_date DATE NULL DEFAULT NULL;
