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
        if(report.coverage()!=null && !report.coverage().complete())throw new com.tianmu.agent.core.AgentException("INVALID_REPORT","报告尚未覆盖全部原文，不能渲染完整报告。");
        StringBuilder h=new StringBuilder("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>视频观众反馈报告</title><style>");
        h.append(STYLE).append("</style></head><body><main><header><p class=\"eyebrow\">观众反馈 · 视频复盘</p><h1>").append(esc(report.title())).append("</h1><p class=\"muted\">").append(esc(report.period())).append("</p>");
        Metrics metrics=report.metrics();
        if(report.sources()!=null) {
            var s=report.sources();
            if(s.selectedSources().size()==1) {
                boolean comments=s.selectedSources().contains(com.tianmu.agent.core.AnalysisContract.Source.COMMENT);
                h.append("<p class=\"context\">本次只分析").append(comments?"评论及回复；未读取弹幕。":"弹幕；未读取评论及回复。")
                        .append("</p><div class=\"metrics\"><div><b>").append(comments?s.comments():s.danmaku()).append("</b><span>")
                        .append(comments?"条评论及回复":"条弹幕").append("</span></div><div><b>").append(s.uniqueViewers()).append("</b><span>位参与观众</span></div></div>")
                        .append("<p class=\"muted\">已分析所选来源全部 ").append(metrics.analyzedMessages()).append(" 条互动；作者发言单独标记。</p>");
            } else {
            if(s.danmaku()==0)h.append("<p class=\"context\">").append(report.allTime()?"截至统计时点，当前视频没有已保留的弹幕记录。":"本统计时段内没有弹幕，不代表视频没有弹幕；其他日期的弹幕未计入本报告。可选择全量或自定义区间查看。").append("</p>");
            if(s.comments()==0)h.append("<p class=\"context\">").append(report.allTime()?"截至统计时点，当前视频没有已保留的评论及回复。":"本统计时段内没有评论及回复，其他日期的评论未计入本报告。").append("</p>");
            h.append("<div class=\"metrics\"><div><b>").append(s.danmaku()).append("</b><span>条弹幕</span></div><div><b>").append(s.comments())
                    .append("</b><span>条评论及回复</span></div><div><b>").append(metrics.topics()).append("</b><span>个观众话题</span></div></div>")
                    .append("<p class=\"muted\">弹幕参与观众 ").append(s.danmakuViewers()).append(" 位，评论参与观众 ").append(s.commentViewers())
                    .append(" 位；两个来源可能有同一人，不相加。跨来源去重后共 ").append(s.uniqueViewers()).append(" 位观众。作者发言：").append(s.danmakuAuthors()).append(" 条弹幕、").append(s.commentAuthors())
                    .append(" 条评论，单独标记，不作为观众需求证据。已分析本时段全部 ").append(metrics.analyzedMessages()).append(" 条互动，分析未抽样。</p>");
            }
        } else h.append("<div class=\"metrics\"><div><b>").append(metrics.messages()).append("</b><span>条弹幕</span></div><div><b>").append(metrics.viewers())
                .append("</b><span>位参与观众</span></div><div><b>").append(metrics.topics()).append("</b><span>个讨论话题</span></div></div><p class=\"muted\">已分析本时段全部 ")
                .append(metrics.analyzedMessages()).append(" 条弹幕。正文引用为阅读示例，分析未抽样。</p>");
        h.append("</header><section class=\"takeaway\"><h2>先看结论</h2>");
        for(String conclusion:report.conclusions())h.append("<p>").append(esc(conclusion)).append("</p>");h.append("</section>");
        if(report.video()!=null){h.append("<section><h2>这段视频的内容</h2><p>").append(esc(report.video().text())).append("</p><p class=\"muted\">画面理解依据 ")
                .append(report.video().sampledPositions()).append(" 个采样位置；不能据此判断完整连续动作或未提供的声音。</p>");
            if(!report.video().hasTranscript())h.append("<p class=\"muted\">未提供音频转写，配乐与收音相关评价仅来自观众表达，尚未核实。</p>");
            h.append("</section>");
        }
        h.append("<section><h2>观众在讨论什么</h2>");int index=0;
        for(TopicCard topic:report.topics()){
            if(index==6)h.append("<details class=\"more-topics\"><summary>查看其他讨论（").append(report.topics().size()-6).append(" 个话题）</summary>");
            h.append("<article><div class=\"topic-head\"><span class=\"number\">").append(String.format(Locale.ROOT,"%02d",++index)).append("</span><h3>").append(esc(topic.title()))
                    .append("</h3><span class=\"badge\">").append(topic.messages()).append(report.sources()==null?" 条相关弹幕":" 条观众反馈").append("</span></div>");
            if(report.sources()!=null) {
                h.append("<p class=\"muted\">");
                if(report.sources().selectedSources().contains(com.tianmu.agent.core.AnalysisContract.Source.DANMAKU))h.append("弹幕 ").append(topic.danmaku()).append(" 条 ");
                if(report.sources().selectedSources().contains(com.tianmu.agent.core.AnalysisContract.Source.COMMENT))h.append("评论及回复 ").append(topic.comments()).append(" 条");
                h.append("</p>");
            }
            h.append("<p>").append(esc(topic.observation())).append("</p>");
            if(!topic.videoContext().isBlank()){
                h.append("<p class=\"context\"><strong>结合画面看：</strong>").append(esc(topic.videoContext())).append(" ");for(Link moment:topic.videoMoments())link(h,moment);h.append("</p>");
            }
            if(!topic.quotes().isEmpty())h.append("<p class=\"label\">观众原话</p>");
            for(Quote quote:topic.quotes()){
                boolean longText=quote.text().codePointCount(0,quote.text().length())>180;
                if(longText)h.append("<details><summary>查看一条较长的观众原话</summary>");
                h.append("<blockquote>“").append(esc(quote.text())).append("”");
                if(report.sources()!=null)h.append("<small>").append("COMMENT".equals(quote.source())?"评论":"弹幕").append("</small>");
                if(quote.occurrences()>1)h.append("<small>").append(quote.playback()==null?"同组表达 ":"该片段同组表达 ").append(quote.occurrences()).append(" 次</small>");
                if(quote.playback()!=null)link(h,quote.playback());h.append("</blockquote>");if(longText)h.append("</details>");
                if(quote.replyContext()!=null && !quote.replyContext().isBlank())h.append("<details class=\"reply-context\"><summary>查看这条回复的上下文</summary><p style=\"white-space:pre-wrap\">").append(esc(quote.replyContext())).append("</p></details>");
            }
            if(!topic.recommendation().isBlank())h.append("<div class=\"action\"><strong>可以怎么做</strong><p>").append(esc(topic.recommendation())).append("</p></div>");h.append("</article>");
        }
        if(report.topics().size()>6)h.append("</details>");
        h.append("</section>");
        if(!report.activeViewers().isEmpty()){
        h.append("<section><h2>").append(report.sources()==null?"互动最活跃的观众":"弹幕互动最活跃的观众").append("</h2><p class=\"muted\">按本时段的弹幕活跃天数与发言情况排列。</p>");int rank=0;
        for(Viewer viewer:report.activeViewers())h.append("<div class=\"fan\"><span class=\"number\">").append(++rank).append("</span><strong>").append(esc(viewer.name()))
                .append("</strong><span>").append(viewer.messages()).append(" 条发言 · 活跃 ").append(viewer.activeDays()).append(" 天</span></div>");
        h.append("</section>");
        }
        if(report.playback().stream().anyMatch(b->b.count()>0) || report.unknownPlayback()>0){
        h.append("<section><h2>视频里哪些片段讨论更多</h2>");lineChart(h,report.playback(),"playback","按视频播放位置统计弹幕","视频播放位置");
        if(report.unknownPlayback()>0)h.append("<p class=\"muted\">另有 ").append(report.unknownPlayback()).append(" 条缺少有效播放位置，已分析其文字，但不关联具体片段。</p>");
        h.append("</section>");
        }
        h.append("<section><h2>观众什么时候讨论得最多</h2><p>").append(esc(report.activitySummary())).append("</p>");lineChart(h,report.activity(),"activity","按互动发送时间统计讨论趋势","互动发送时间 · 北京时间");
        h.append("<p class=\"muted\">这里表示收到互动的日期和时段，与视频内的播放位置不同；评论没有播放位置，不纳入视频片段的讨论量统计。</p></section><details class=\"method\"><summary>统计与处理说明</summary><p>分析范围为页面所示区间，结束时刻不计入；只包含当前仍保留的已入库记录。弹幕重复原文合并处理并保留全部次数，评论保留独立身份及回复关系。完整文本分批分析后汇总，区间外父评论仅作上下文，不额外计数。正文原话在整份报告中统一去重，仅展示有区别的表达。建议来自观众表达与可见画面，不表示问题已经被证实。</p><p>数据读取时间：")
                .append(esc(report.dataAsOf())).append("；分析完成 ").append(metrics.analyzedMessages()).append(" / ").append(metrics.messages()).append(" 条。</p></details><footer>视频观众反馈报告 · ");
        link(h,new Link("返回视频",report.videoUrl()));return h.append("</footer></main></body></html>").toString();
    }
    private static void lineChart(StringBuilder h,List<Bar> values,String id,String title,String axis){
        if(values.stream().noneMatch(b->b.count()>0)){h.append("<p class=\"muted\">本时段暂无可绘制的记录。</p>");return;}
        double raw=Math.max(1,values.stream().mapToLong(Bar::count).max().orElse(1))/4.0,power=Math.pow(10,Math.floor(Math.log10(raw))),ratio=raw/power;
        double step=Math.max(1,(ratio<=1?1:ratio<=2?2:ratio<=5?5:10)*power);
        double max=values.stream().mapToLong(Bar::count).max().orElse(1),ceiling=Math.max(step,Math.ceil(max/step)*step);
        h.append("<svg class=\"line-chart\" viewBox=\"0 0 760 300\" role=\"img\" aria-labelledby=\"").append(id).append("-title ").append(id).append("-desc\"><title id=\"")
                .append(id).append("-title\">").append(esc(title)).append("</title><desc id=\"").append(id).append("-desc\">");
        for(Bar value:values)h.append(esc(value.label())).append("：").append(value.count()).append("条；");
        h.append("</desc><defs><linearGradient id=\"").append(id).append("-fill\" x1=\"0\" y1=\"0\" x2=\"0\" y2=\"1\"><stop offset=\"0%\" stop-color=\"#67a994\" stop-opacity=\".25\"/><stop offset=\"100%\" stop-color=\"#67a994\" stop-opacity=\".02\"/></linearGradient></defs>");
        for(int i=0;i<=Math.round(ceiling/step);i++){
            double y=230-i*step/ceiling*202;
            h.append("<line x1=\"54\" x2=\"738\" y1=\"").append(number(y)).append("\" y2=\"").append(number(y)).append("\" stroke=\"#e5ecef\"/>")
                    .append("<text x=\"44\" y=\"").append(number(y+5)).append("\" text-anchor=\"end\" fill=\"#7b8b96\" font-size=\"14\">").append(String.format(Locale.ROOT,"%.0f",i*step)).append("</text>");
        }
        StringBuilder points=new StringBuilder();
        for(int i=0;i<values.size();i++){double x=values.size()==1?396:54+i*684.0/(values.size()-1),y=230-values.get(i).count()/ceiling*202;points.append(number(x)).append(',').append(number(y)).append(' ');}
        double first=values.size()==1?396:54,last=values.size()==1?396:738;
        h.append("<polygon points=\"").append(number(first)).append(",230 ").append(points).append(number(last)).append(",230\" fill=\"url(#").append(id).append("-fill)\"/>")
                .append("<polyline class=\"trend-line\" points=\"").append(points).append("\" fill=\"none\" stroke=\"#387f6c\" stroke-width=\"3\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>");
        int labelEvery=Math.max(1,(values.size()+5)/6);
        for(int i=0;i<values.size();i++){
            Bar value=values.get(i);double x=values.size()==1?396:54+i*684.0/(values.size()-1),y=230-value.count()/ceiling*202;boolean linked=value.url()!=null && validUrl(value.url());
            if(linked)h.append("<a target=\"_blank\" href=\"").append(esc(value.url())).append("\">");
            h.append("<circle cx=\"").append(number(x)).append("\" cy=\"").append(number(y)).append("\" r=\"5\" fill=\"#387f6c\" stroke=\"white\" stroke-width=\"2\"><title>").append(esc(value.label())).append(" · ").append(value.count()).append(" 条</title></circle>");
            if(linked)h.append("</a>");
            if(values.size()<=16 && value.count()>0)h.append("<text x=\"").append(number(x)).append("\" y=\"").append(number(y-11)).append("\" text-anchor=\"middle\" fill=\"#326f5e\" font-size=\"14\">").append(value.count()).append("</text>");
            if((i%labelEvery==0 && (i==0 || i<=values.size()-1-labelEvery)) || i==values.size()-1){
                String label=value.label().split(" — ")[0];String[] parts=label.split(" ",2);String anchor=i==0?"start":i==values.size()-1?"end":"middle";
                h.append("<text x=\"").append(number(x)).append("\" y=\"253\" text-anchor=\"").append(anchor).append("\" fill=\"#7b8b96\" font-size=\"14\">").append(esc(parts[0]));
                if(parts.length>1)h.append("<tspan x=\"").append(number(x)).append("\" dy=\"17\">").append(esc(parts[1])).append("</tspan>");h.append("</text>");
            }
        }
        h.append("<text x=\"10\" y=\"16\" fill=\"#7b8b96\" font-size=\"13\">条</text><text x=\"396\" y=\"295\" text-anchor=\"middle\" fill=\"#7b8b96\" font-size=\"13\">").append(esc(axis)).append("</text></svg>");
    }
    private static String number(double value){return String.format(Locale.ROOT,"%.2f",value);}
    private static boolean validUrl(String url){return url.matches("/video/[1-9][0-9]*(?:\\?t=[0-9]+(?:\\.[0-9]+)?)?");}
    private static void link(StringBuilder h,Link link){
        if(!validUrl(link.url())){h.append(esc(link.label()));return;}
        h.append(" <a target=\"_blank\" rel=\"noopener\" href=\"").append(esc(link.url())).append("\">").append(esc(link.label())).append(" ↗</a>");
    }
    private static String esc(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static final String STYLE="*{box-sizing:border-box}body{margin:0;background:#f4f6f8;color:#24323d;font:16px/1.8 system-ui,-apple-system,'Microsoft YaHei',sans-serif}main{max-width:940px;margin:40px auto;padding:0 28px}header,section,.method{background:#fff;border:1px solid #e7ebef;border-radius:18px;padding:30px;margin:20px 0}h1{font-size:36px;margin:8px 0 18px;color:#182b36}h2{font-size:22px;margin:0 0 18px}h3{font-size:19px;margin:0;flex:1}p{margin:10px 0}.eyebrow,.label{font-size:13px;color:#40786d;font-weight:650}.muted,small{color:#73818b;font-size:13px}.metrics{display:flex;gap:70px;margin:26px 0}.metrics div{display:grid}.metrics b{font-size:34px;color:#21685d}.metrics span{font-size:14px;color:#6c7b85}.takeaway{border-left:5px solid #468f7d;background:#f7fcfa}article{padding:24px 0;border-top:1px solid #e8edef}.topic-head{display:flex;gap:14px;align-items:center}.number{color:#94a8a2;font-size:15px}.badge{font-size:12px;padding:4px 10px;background:#eef5f2;color:#407467;border-radius:12px}blockquote{margin:12px 0;padding:12px 16px;background:#f7f9fb;border-left:3px solid #d3e2de;border-radius:4px;font-size:14px}blockquote small{margin-left:10px}a{color:#276d64;text-decoration:none;font-size:13px}a:hover{text-decoration:underline}.context{font-size:14px;color:#526772}.action{background:#f3f8f6;border-radius:10px;padding:16px 18px;margin-top:20px}.action strong{font-size:14px;color:#387360}.action p{margin-bottom:0}.fan{display:flex;gap:18px;padding:12px 0;border-bottom:1px solid #edf1f3}.fan strong{flex:1;font-size:14px}.fan>span:last-child{font-size:13px;color:#73818b}.bar-row{display:grid;grid-template-columns:180px 1fr 35px;gap:14px;align-items:center;margin:10px 0;font-size:12px}.track{height:8px;border-radius:5px;background:#edf2f4;overflow:hidden}.track i{display:block;height:100%;background:#86b6a6;border-radius:5px}.bar-row b{text-align:right}.method{font-size:13px;color:#73818b}.line-chart{display:block;width:100%;height:auto;margin:22px 0 6px}summary{cursor:pointer}footer{text-align:center;color:#91a0aa;font-size:12px;margin:30px 0}@media(max-width:600px){main{margin:12px auto;padding:0 12px}header,section,.method{padding:20px}h1{font-size:28px}.metrics{gap:32px}.topic-head{flex-wrap:wrap}.topic-head h3{min-width:65%}.bar-row{grid-template-columns:115px 1fr 25px;gap:8px}.fan{gap:9px}}";
}
