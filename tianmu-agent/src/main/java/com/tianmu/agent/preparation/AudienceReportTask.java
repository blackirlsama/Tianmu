package com.tianmu.agent.preparation;

import com.tianmu.agent.core.ConversationTopics;
public final class AudienceReportTask {
    private AudienceReportTask(){}
    public static boolean requested(String text){
        return !ConversationTopics.general(text) && (text.matches("(?s).*(报告|复盘).*") || text.contains("弹幕") && text.contains("评论") && text.contains("分析"))
                && text.matches("(?s).*(评论|观众|反馈|综合).*");
    }
}
