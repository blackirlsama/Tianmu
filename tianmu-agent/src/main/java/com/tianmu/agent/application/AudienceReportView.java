package com.tianmu.agent.application;

import com.tianmu.agent.core.AudienceData.*;
import java.util.*;

/** Shared topic cards for the private report; untrusted text is always escaped. */
public final class AudienceReportView {
    private AudienceReportView() {}
    public static String render(Overview data,List<Topic> topics,long videoId) {
        StringBuilder h=new StringBuilder("<section class=\"section\"><h2>主要话题、视频上下文与建议</h2><p>");
        long classified=topics.stream().mapToLong(Topic::messages).sum();
        h.append("全量弹幕 ").append(data.totalMessages()).append(" 条；本次读取 ").append(data.corpus().size())
                .append(" 组同文内容，覆盖 ").append(data.corpusMessages()).append(" 条，已归入话题 ").append(classified)
                .append(" 条，其余未分类。话题数按归入的原始同文组计算，不是模型估计。高频同文优先，模型每组最多阅读前80字，例子最多展示160字；归类和建议需复核，不能当作全部观点的无偏样本。</p>");
        Map<String,TextGroup> groups=new HashMap<>();data.corpus().forEach(g->groups.put(g.id(),g));
        if(topics.isEmpty()) h.append("<p>当前没有形成可靠话题，不生成无依据的建议。</p>");
        for(Topic t:topics) {
            h.append("<article class=\"insight-card\"><h3>").append(esc(t.title())).append(" · ").append(t.messages()).append(" 条</h3><p>").append(esc(t.observation())).append("</p>");
            if(!t.videoContext().isBlank()){
                h.append("<p><strong>相关画面：</strong>").append(esc(t.videoContext())).append(" · ");
                for(long at:t.videoAtMs())h.append("<a target=\"_blank\" rel=\"noopener\" href=\"/video/").append(videoId).append("?t=").append(at/1000.0).append("\">")
                        .append(String.format(Locale.ROOT,"%02d:%02d.%03d",at/60000,at/1000%60,at%1000)).append("</a> ");
                h.append("（采样画面位置；相关性不表示因果）</p>");
            }
            if(!t.recommendation().isBlank())h.append("<div class=\"recommendation\"><strong>改进建议</strong><p>").append(esc(t.recommendation())).append("</p></div>");
            long[] trend=new long[12];boolean complete=true;
            for(String id:t.sampleIds()){var group=groups.get(id);if(group.trend().isEmpty()){complete=false;break;}for(int i=0;i<12;i++)trend[i]=Math.addExact(trend[i],group.trend().get(i));}
            if(complete)h.append("<p>发送时间趋势（对应上方12个时间段）：").append(esc(Arrays.toString(trend))).append("。仅含已归入本话题的同文组。</p>");
            h.append("<h4>弹幕例子</h4>");
            for(String id:t.sampleIds().stream().limit(3).toList()) {
                TextGroup g=groups.get(id);
                h.append("<blockquote>").append(esc(g.text())).append(g.truncated()?"（已截短）":"").append("<small> · 同文 ").append(g.count()).append(" 条 · #").append(esc(id)).append("</small>");
                if(g.playbackMs()!=null)h.append(" <a target=\"_blank\" rel=\"noopener\" href=\"/video/").append(videoId).append("?t=").append(g.playbackMs()/1000.0).append("\">查看播放位置</a>");
                h.append("</blockquote>");
            }
            h.append("</article>");
        }
        h.append("</section><section class=\"section\"><h2>最铁互动用户</h2><p>按活跃天数、不同原文数、发言数排序。依据现有已入库用户标识，不等同于关注关系或忠诚度；重复同文不会提高不同原文数。</p><div class=\"table-wrap\"><table><thead><tr><th>用户</th><th>弹幕</th><th>活跃天数</th><th>不同原文</th></tr></thead><tbody>");
        for(Interaction f:data.interactions())h.append("<tr><td>用户 ").append(esc(f.userId())).append("</td><td>").append(f.messages()).append("</td><td>").append(f.activeDays()).append("</td><td>").append(f.distinctTexts()).append("</td></tr>");
        h.append("</tbody></table></div></section><section class=\"section\"><h2>按视频播放位置统计</h2><p>未知或超出支持范围的位置：").append(data.unknownPlayback()).append(" 条，不参与播放位置关联。此图与按发送时间的趋势分开。</p><div class=\"table-wrap\"><table><thead><tr><th>播放范围</th><th>弹幕</th></tr></thead><tbody>");
        for(Position p:data.playback())h.append("<tr><td>").append(p.startMs()/1000).append("—").append(p.endMs()/1000).append("秒</td><td>").append(p.count()).append("</td></tr>");
        return h.append("</tbody></table></div></section>").toString();
    }
    private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
