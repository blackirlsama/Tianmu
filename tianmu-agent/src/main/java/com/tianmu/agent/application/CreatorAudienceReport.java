package com.tianmu.agent.application;

import com.tianmu.agent.core.VideoContent;
import com.tianmu.agent.core.AudienceData.Topic;
import com.tianmu.agent.core.DanmakuTypes.Dataset;
import com.tianmu.agent.application.CreatorReportDocument.*;
import java.util.*;

/** Presentation only: HTML consumes the same versioned JSON document exposed in the tool result. */
public final class CreatorAudienceReport {
    private CreatorAudienceReport() {}
    public static String render(Dataset data,List<Topic> topics,VideoContent.Facts video){return render(CreatorReportDocument.from(data,topics,video));}
    public static String render(CreatorReportDocument report){
        StringBuilder h=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>视频观众反馈报告</title><style>");
        h.append(STYLE).append("</style></head><body><main><header><p class=\"eyebrow\">观众反馈 · 视频复盘</p><h1>").append(esc(report.title())).append("</h1><p class=\"muted\">").append(esc(report.period())).append("</p>");
        if(report.synthetic())h.append("<p class=\"badge\">本报告包含模拟测试数据</p>");
        Metrics metrics=report.metrics();
        h.append("<div class=\"metrics\"><div><b>").append(metrics.messages()).append("</b><span>条弹幕</span></div><div><b>").append(metrics.viewers())
                .append("</b><span>位参与观众</span></div><div><b>").append(metrics.topics()).append("</b><span>个讨论话题</span></div></div><p class=\"muted\">已分析本时段全部 ")
                .append(metrics.analyzedMessages()).append(" 条弹幕。正文引用为阅读示例，分析未抽样。</p></header><section class=\"takeaway\"><h2>先看结论</h2>");
        for(String conclusion:report.conclusions())h.append("<p>").append(esc(conclusion)).append("</p>");h.append("</section>");
        if(report.video()!=null)h.append("<section><h2>这段视频的内容</h2><p>").append(esc(report.video().text())).append("</p><p class=\"muted\">画面理解依据 ")
                .append(report.video().sampledPositions()).append(" 个采样位置；不能据此判断完整连续动作或未提供的声音。</p></section>");
        h.append("<section><h2>观众在讨论什么</h2>");int index=0;
        for(TopicCard topic:report.topics()){
            h.append("<article><div class=\"topic-head\"><span class=\"number\">").append(String.format(Locale.ROOT,"%02d",++index)).append("</span><h3>").append(esc(topic.title()))
                    .append("</h3><span class=\"badge\">").append(topic.messages()).append(" 条相关弹幕</span></div><p>").append(esc(topic.observation())).append("</p>");
            if(!topic.videoContext().isBlank()){
                h.append("<p class=\"context\"><strong>结合画面看：</strong>").append(esc(topic.videoContext())).append(" ");for(Link moment:topic.videoMoments())link(h,moment);h.append("</p>");
            }
            if(!topic.quotes().isEmpty())h.append("<p class=\"label\">观众原话</p>");
            for(Quote quote:topic.quotes()){
                boolean longText=quote.text().codePointCount(0,quote.text().length())>180;
                if(longText)h.append("<details><summary>查看一条较长的观众原话</summary>");
                h.append("<blockquote>“").append(esc(quote.text())).append("”");
                if(quote.occurrences()>1)h.append("<small>同样的表达共 ").append(quote.occurrences()).append(" 次</small>");
                if(quote.playback()!=null)link(h,quote.playback());h.append("</blockquote>");if(longText)h.append("</details>");
            }
            if(!topic.recommendation().isBlank())h.append("<div class=\"action\"><strong>可以怎么做</strong><p>").append(esc(topic.recommendation())).append("</p></div>");h.append("</article>");
        }
        h.append("</section><section><h2>互动最活跃的观众</h2><p class=\"muted\">按本时段的活跃天数与发言情况排列。</p>");int rank=0;
        for(Viewer viewer:report.activeViewers())h.append("<div class=\"fan\"><span class=\"number\">").append(++rank).append("</span><strong>").append(esc(viewer.name()))
                .append("</strong><span>").append(viewer.messages()).append(" 条发言 · 活跃 ").append(viewer.activeDays()).append(" 天</span></div>");
        h.append("</section><section><h2>视频里哪些片段讨论更多</h2>");bars(h,report.playback());
        if(report.unknownPlayback()>0)h.append("<p class=\"muted\">另有 ").append(report.unknownPlayback()).append(" 条缺少有效播放位置，已分析其文字，但不关联具体片段。</p>");
        h.append("</section><section><h2>观众什么时候讨论得最多</h2><p>").append(esc(report.activitySummary())).append("</p>");bars(h,report.activity());
        h.append("<p class=\"muted\">这里表示收到弹幕的日期和时段，与视频内的播放位置不同。</p></section><details class=\"method\"><summary>统计与处理说明</summary><p>分析范围为页面所示区间，结束时刻不计入；只包含当前仍保留的已入库弹幕。同一句原文合并处理并保留全部次数，完整文本分批分析后汇总。正文原话在整份报告中统一去重，仅展示有区别的表达。建议来自观众表达与可见画面，不表示问题已经被证实。</p><p>数据读取时间：")
                .append(esc(report.dataAsOf())).append("；分析完成 ").append(metrics.analyzedMessages()).append(" / ").append(metrics.messages()).append(" 条。</p></details><footer>视频观众反馈报告 · ");
        link(h,new Link("返回视频",report.videoUrl()));return h.append("</footer></main></body></html>").toString();
    }
    private static void bars(StringBuilder h,List<Bar> bars){
        long max=bars.stream().mapToLong(Bar::count).max().orElse(1);
        for(Bar bar:bars){
            h.append("<div class=\"bar-row\"><span>");if(bar.url()!=null)link(h,new Link(bar.label(),bar.url()));else h.append(esc(bar.label()));
            h.append("</span><div class=\"track\"><i style=\"width:").append(max<=0?0:Math.round(bar.count()*100.0/max)).append("%\"></i></div><b>").append(bar.count()).append("</b></div>");
        }
    }
    private static void link(StringBuilder h,Link link){
        if(!link.url().matches("/video/[1-9][0-9]*(?:\\?t=[0-9]+(?:\\.[0-9]+)?)?")){h.append(esc(link.label()));return;}
        h.append(" <a target=\"_blank\" rel=\"noopener\" href=\"").append(esc(link.url())).append("\">").append(esc(link.label())).append(" ↗</a>");
    }
    private static String esc(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static final String STYLE="*{box-sizing:border-box}body{margin:0;background:#f4f6f8;color:#24323d;font:16px/1.8 system-ui,-apple-system,'Microsoft YaHei',sans-serif}main{max-width:940px;margin:40px auto;padding:0 28px}header,section,.method{background:#fff;border:1px solid #e7ebef;border-radius:18px;padding:30px;margin:20px 0}h1{font-size:36px;margin:8px 0 18px;color:#182b36}h2{font-size:22px;margin:0 0 18px}h3{font-size:19px;margin:0;flex:1}p{margin:10px 0}.eyebrow,.label{font-size:13px;color:#40786d;font-weight:650}.muted,small{color:#73818b;font-size:13px}.metrics{display:flex;gap:70px;margin:26px 0}.metrics div{display:grid}.metrics b{font-size:34px;color:#21685d}.metrics span{font-size:14px;color:#6c7b85}.takeaway{border-left:5px solid #468f7d;background:#f7fcfa}article{padding:24px 0;border-top:1px solid #e8edef}.topic-head{display:flex;gap:14px;align-items:center}.number{color:#94a8a2;font-size:15px}.badge{font-size:12px;padding:4px 10px;background:#eef5f2;color:#407467;border-radius:12px}blockquote{margin:12px 0;padding:12px 16px;background:#f7f9fb;border-left:3px solid #d3e2de;border-radius:4px;font-size:14px}blockquote small{margin-left:10px}a{color:#276d64;text-decoration:none;font-size:13px}a:hover{text-decoration:underline}.context{font-size:14px;color:#526772}.action{background:#f3f8f6;border-radius:10px;padding:16px 18px;margin-top:20px}.action strong{font-size:14px;color:#387360}.action p{margin-bottom:0}.fan{display:flex;gap:18px;padding:12px 0;border-bottom:1px solid #edf1f3}.fan strong{flex:1;font-size:14px}.fan>span:last-child{font-size:13px;color:#73818b}.bar-row{display:grid;grid-template-columns:180px 1fr 35px;gap:14px;align-items:center;margin:10px 0;font-size:12px}.track{height:8px;border-radius:5px;background:#edf2f4;overflow:hidden}.track i{display:block;height:100%;background:#86b6a6;border-radius:5px}.bar-row b{text-align:right}.method{font-size:13px;color:#73818b}summary{cursor:pointer}footer{text-align:center;color:#91a0aa;font-size:12px;margin:30px 0}@media(max-width:600px){main{margin:12px auto;padding:0 12px}header,section,.method{padding:20px}h1{font-size:28px}.metrics{gap:32px}.topic-head{flex-wrap:wrap}.topic-head h3{min-width:65%}.bar-row{grid-template-columns:115px 1fr 25px;gap:8px}.fan{gap:9px}}";
}
