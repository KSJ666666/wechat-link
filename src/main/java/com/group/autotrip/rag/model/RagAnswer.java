package com.group.autotrip.rag.model;

import java.util.List;

/**
 * RAG 问答结果。
 *
 * @param answer  生成答案文本
 * @param sources 用于生成答案的知识块（按重排后的顺序）
 */
public record RagAnswer(
        String answer,
        List<ScoredChunk> sources) {

    /** 空来源列表兜底 */
    public RagAnswer {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 参考来源标题，用顿号连接，便于追加到回复末尾 */
    public String sourceTitles() {
        return sources.stream()
                .map(s -> s.chunk().title())
                .distinct()
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
    }
}
