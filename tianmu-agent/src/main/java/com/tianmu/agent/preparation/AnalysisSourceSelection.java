package com.tianmu.agent.preparation;

import com.tianmu.agent.core.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.tianmu.agent.core.AnalysisContract.Source.*;

/** Conservative explicit-source grammar. Unclear negation is clarified rather than widening reads. */
public final class AnalysisSourceSelection {
    private AnalysisSourceSelection() {}
    private static final Pattern VIDEO_WORDS = Pattern.compile("视频内容|视频画面|画面|镜头|剧情|对白|字幕|视频[0-9]+的?(?:实际)?内容|(?:看|读取|读|结合|加上)(?:这个|该|当前|一下|同一|实际)?视频(?![0-9]*(?:的)?(?:评论|弹幕))|视频(?:也|还要)(?:看|分析|读)|视频(?:[0-9]+)?(?:和|与|、)(?:评论|弹幕|观众反馈)");
    private static final Pattern NEGATIVE = Pattern.compile("不(?:要|用|必|需要|读取|分析|看|结合|是)|无需|别(?:看|读|分析)|排除|去掉");
    private static final Pattern NO_REPORT = Pattern.compile("(?:不要|不用|不需要|无需|别|不)(?:再)?(?:生成|制作|输出|导出|做|写)?\\s*(?:HTML|html)?\\s*(?:报告|复盘)");
    public static boolean mentions(String text) { return text.contains("评论") || text.contains("弹幕") || VIDEO_WORDS.matcher(text).find(); }
    public static AnalysisContract select(String text, AnalysisContract fallback) {
        if (fallback == null) return null;
        if (text.matches("(?s).*(?:不是不|并非不|不要不|不能不|不需要不|不要漏|不能漏|别漏|不只|不仅|不光).*")) ambiguous();
        var positive = EnumSet.noneOf(AnalysisContract.Source.class);
        var excluded = EnumSet.noneOf(AnalysisContract.Source.class);
        boolean noReport = NO_REPORT.matcher(text).find();
        String sourceText = NO_REPORT.matcher(text).replaceAll("");
        if (noReport && sourceText.matches("(?s).*(?:生成|制作|输出|导出|做|写)(?:报告|复盘).*")) ambiguous();
        String clauses = sourceText.replaceAll("(?=只(?:看|分析|读取|统计)|仅(?:看|分析|读取)|只要)", "，")
                .replace("而是", "，").replace("但是", "，").replace("不过", "，");
        for (String clause : clauses.split("[，,。；;！？!?\\n]")) {
            var mentioned = EnumSet.noneOf(AnalysisContract.Source.class);
            if (clause.contains("评论") || clause.contains("回复")) mentioned.add(COMMENT);
            if (clause.contains("弹幕")) mentioned.add(DANMAKU);
            if (VIDEO_WORDS.matcher(clause).find()) mentioned.add(VIDEO);
            if(mentioned.contains(VIDEO) && !mentioned.contains(COMMENT) && !mentioned.contains(DANMAKU)
                    && clause.matches("(?s).*(?:观众|反馈).*") && !clause.matches("(?s).*(?:只|仅).*") && !NEGATIVE.matcher(clause).find()) {
                mentioned.add(COMMENT);mentioned.add(DANMAKU);
            }
            if (mentioned.isEmpty()) continue;
            if(clause.matches("(?s).*(?:只看|只分析|只读取|只统计|仅看|仅分析|只要).*")) { positive.clear(); excluded.addAll(EnumSet.allOf(AnalysisContract.Source.class)); }
            if (NEGATIVE.matcher(clause).find()) {
                if (clause.matches("(?s).*(?:原话|引用|有人说|他说|“|\").*")) ambiguous();
                excluded.addAll(mentioned); positive.removeAll(mentioned);
            } else { positive.addAll(mentioned); excluded.removeAll(mentioned); }
        }
        var selected = positive.isEmpty() ? EnumSet.copyOf(fallback.sources()) : positive;
        if(!positive.isEmpty() && !text.matches("(?s).*(?:只|仅).*") && text.matches("(?s)^(?:继续|再|加上|也看|同时).*")) {
            selected=EnumSet.copyOf(fallback.sources());selected.addAll(positive);
        }
        selected.removeAll(excluded);
        if (selected.isEmpty()) ambiguous();
        boolean reportRequired = !noReport && (fallback.reportRequired() || text.matches("(?s).*(报告|复盘).*"));
        return new AnalysisContract(selected, reportRequired,fallback.videoStartMs(),fallback.videoEndMs());
    }
    private static void ambiguous() {
        throw new AgentException("AMBIGUOUS_ANALYSIS_SOURCES", "请说明本次需要分析视频内容、评论、弹幕中的哪些来源，以及明确不读取哪些来源。");
    }
}
