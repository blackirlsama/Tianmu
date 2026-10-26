package com.tianmu.agent.application;

import com.tianmu.agent.core.AudienceData.TextGroup;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/** Full-text weighted term frequencies; Chinese segmentation uses Lucene's bundled dictionary. */
final class ReportWordCloud {
    private ReportWordCloud() {}
    private static final Pattern TERM=Pattern.compile("[\\p{IsHan}A-Za-z][\\p{IsHan}A-Za-z0-9_-]{0,15}");
    private static final Set<String> STOP=new HashSet<>(Arrays.asList(("我 你 他 她 它 这 那 有 是 在 不 也 和 的 了 吗 啊 呀 呢 吧 哦 就 都 很 太 好 想 看 能 要 点 给 多 再 小 大 真 还 去 又 来 着 个 更 到 一 二 三 四 五 六 七 八 九 十 么 为 与 或 等 把 被 会 用 上 下 无 让 对 得 才 可 从 这个 那个 一个 一下 一些 这种 可以 觉得 就是 还是 真的 已经 我们 你们 他们 这么 那么 什么 怎么 如果 有没有 这里 看着 看起来 好像 时候 一点 不用 不要 不能 因为 所以 以及 其实 可能 还有 比较 感觉 视频 弹幕 观众 希望 能不能 想要 想看 只 做 挺 没有 不是 有点 出来 知道 请问 当然 email phone").split(" ")));
    static List<CreatorReportDocument.Word> terms(List<TextGroup> groups){
        Map<String,Long> counts=new HashMap<>();
        try(var analyzer=new SmartChineseAnalyzer()){
            for(TextGroup group:groups)try(var stream=analyzer.tokenStream("danmaku",group.text())){
                var term=stream.addAttribute(CharTermAttribute.class);stream.reset();
                while(stream.incrementToken()){
                    String word=term.toString().toLowerCase(Locale.ROOT);
                    if(TERM.matcher(word).matches() && !STOP.contains(word))counts.merge(word,group.count(),Math::addExact);
                }
                stream.end();
            }
        }catch(IOException e){throw new com.tianmu.agent.core.AgentException("REPORT_TERMS_UNAVAILABLE","弹幕词频暂时无法生成，请重试。");}
        return counts.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(40).map(e->new CreatorReportDocument.Word(e.getKey(),e.getValue())).toList();
    }
}
