package com.group.autotrip.rag;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 极简 RAG：内置小知识库 + 关键词/二元组重叠打分检索 + Prompt 增强。
 *
 * <p>当消息命中 RAG 关键词（帮助/使用/功能/关于…）时，由 {@code MessageRouter}
 * 调用 {@link #buildPrompt} 把检索到的文档拼进 Prompt，再交给 LLM 回答。
 */
@Service
public class RagService {

    public record RagDoc(String title, String content) {
    }

    private final List<RagDoc> docs = List.of(
            new RagDoc("功能介绍",
                    "本微信机器人支持以下能力：\n" +
                            "1. 数学计算：发送如「23.5 乘以 4 等于多少」，调用 calculate 工具返回精确结果。\n" +
                            "2. 当前时间：发送如「现在几点了」，调用 get_time 工具返回当前时间。\n" +
                            "3. 股票行情：发送如「查贵州茅台股价」，调用 get_stock 工具返回实时行情。\n" +
                            "4. 文生图：发送「画 一只猫」，生成图片并发送。\n" +
                            "5. 语音播报：发送「语音 你好」，合成 mp3 发送。\n" +
                            "6. 天气查询：发送「郑州天气」，返回心知天气实时数据。"),
            new RagDoc("使用方法",
                    "使用步骤：\n" +
                            "1. 浏览器打开 http://localhost:8080/ 扫码登录微信。\n" +
                            "2. 登录成功后，在微信里直接给机器人发消息即可。\n" +
                            "3. 消息路由顺序：先匹配 Skill 关键词（工具执行），再匹配 RAG 关键词（文档增强），最后走 LLM 闲聊兜底。"),
            new RagDoc("关于",
                    "本项目基于 Spring Boot 4.1.0 + 阿里云百炼(DashScope) + 微信 iLink SDK 构建。\n" +
                            "Function Calling 工具采用 JSON Schema 描述函数签名，由 CustomTools 注册表自动收集。")
    );

    /** 关键词/二元组重叠打分，返回 topN 相关文档（无相关时仍返回前 topN 篇，保证 RAG 有上下文） */
    public List<RagDoc> retrieve(String query, int topN) {
        Set<String> q = tokenize(query);
        List<RagDoc> scored = new ArrayList<>(docs);
        scored.sort((a, b) -> Integer.compare(score(b, q), score(a, q)));
        return scored.subList(0, Math.min(topN, scored.size()));
    }

    private int score(RagDoc doc, Set<String> q) {
        Set<String> d = tokenize(doc.title() + " " + doc.content());
        int s = 0;
        for (String w : q) {
            if (d.contains(w)) {
                s++;
            }
        }
        return s;
    }

    private Set<String> tokenize(String s) {
        Set<String> set = new HashSet<>();
        if (s == null) {
            return set;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        // 英文/数字 token
        for (String t : lower.split("[^a-z0-9]+")) {
            if (t.length() >= 2) {
                set.add(t);
            }
        }
        // 中文二元组
        String han = lower.replaceAll("[^\\p{IsHan}]", "");
        for (int i = 0; i + 2 <= han.length(); i++) {
            set.add(han.substring(i, i + 2));
        }
        return set;
    }

    /** 构造 RAG 增强提示词：检索到的文档 + 用户问题 */
    public String buildPrompt(String userText) {
        List<RagDoc> top = retrieve(userText, 2);
        StringBuilder ctx = new StringBuilder();
        ctx.append("请根据以下参考资料回答用户问题。资料里有的就据实回答，没有的请如实说明。\n\n");
        for (RagDoc d : top) {
            ctx.append("【").append(d.title()).append("】\n").append(d.content()).append("\n\n");
        }
        ctx.append("用户问题：").append(userText);
        return ctx.toString();
    }
}
