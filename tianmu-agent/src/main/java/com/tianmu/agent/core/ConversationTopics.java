package com.tianmu.agent.core;

import java.util.regex.Pattern;

/** Distinguishes explicit general advice/definitions from requests to read business data. */
public final class ConversationTopics {
    private ConversationTopics() {}
    private static final Pattern GENERAL = Pattern.compile("什么是|是什么意思|解释|科普|(?:如何|怎样|怎么)(?:提高|提升|改善|增加|设计|分析|理解)|话术|开场白|通用建议|运营建议|互动建议");
    private static final Pattern DATA_ACTION = Pattern.compile("(?:请|帮我|给我|直接)(?:查询|查一下|统计|分析)|查询|统计|有多少|共有|总共|几条|生成.{0,12}报告|出.{0,8}报告");
    private static final Pattern VIDEO_CONTENT = Pattern.compile("画面|剧情|镜头|对白|(?:视频|片段).{0,12}(?:内容|讲了什么|发生了什么)|(?:看懂|看看|看一下|分析).{0,10}(?:视频|片段)");
    private static final Pattern VIEW_ACTION = Pattern.compile("分析|概括|描述|总结|讲了什么|发生了什么|看懂|看看|看一下|说明|是什么|有什么");
    public static boolean general(String text) { return GENERAL.matcher(text).find() && !DATA_ACTION.matcher(text).find(); }
    public static boolean videoContent(String text) { return !general(text) && VIDEO_CONTENT.matcher(text).find() && VIEW_ACTION.matcher(text).find(); }
}
