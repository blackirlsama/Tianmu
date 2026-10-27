package com.tianmu.agent.preparation;

import com.tianmu.agent.core.ConversationTopics;

public final class CommentAnalysisTask {
    private CommentAnalysisTask() {}
    public static boolean requested(String text) {
        return !AudienceReportTask.requested(text) && text.contains("评论") && !text.contains("弹幕") && !ConversationTopics.general(text)
                && text.matches("(?s).*(分析|统计|查询|查一下|讨论|反馈|几条|多少|总结).*")
                && !text.matches("(?s).*(生成|写|发送|发布|智能评论|报告).*");
    }
}
