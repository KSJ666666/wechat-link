package com.group.autotrip.rag.retrieve;

import com.group.autotrip.rag.model.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * ⑧ 增强 Prompt 构建：把重排后的知识块拼进提示词，要求模型只依据资料回答并标注来源。
 */
@Component
public class RagPromptBuilder {

    /** 组装增强 Prompt（单条消息，直接交给 LLM 生成） */
    public String build(String query, List<ScoredChunk> sources) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是旅行指南助手，请严格依据下面的参考资料回答用户问题。\n")
                .append("要求：只使用资料中提供的信息，不要编造资料中没有的内容；")
                .append("涉及具体景点时注明景点名称和所在城市；如果资料不足以回答，请直接说明。\n\n")
                .append("参考资料：\n");
        int index = 1;
        for (ScoredChunk source : sources) {
            sb.append('[').append(index++).append("] ")
                    .append(source.chunk().title())
                    .append("（").append(source.chunk().city())
                    .append("，评分 ").append(formatRating(source.chunk().rating()));
            if (source.chunk().isHot()) {
                sb.append("，热门");
            }
            sb.append("）：").append(source.chunk().text()).append('\n');
        }
        sb.append("\n用户问题：").append(query);
        return sb.toString();
    }

    private static String formatRating(double rating) {
        if (rating <= 0) {
            return "未知";
        }
        return String.format(Locale.ROOT, "%.1f", rating);
    }
}
