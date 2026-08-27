package com.group.autotrip.rag.model;

/**
 * 一次检索命中的知识块及其得分。
 *
 * @param chunk  知识块
 * @param score  得分（向量相似度 / VSM 相似度 / RRF 融合分 / 重排分）
 * @param source 命中来源：VECTOR / KEYWORD / VECTOR+KEYWORD
 */
public record ScoredChunk(
        GuideChunk chunk,
        double score,
        String source) {
}
