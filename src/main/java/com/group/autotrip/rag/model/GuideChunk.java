package com.group.autotrip.rag.model;

import java.util.List;

/**
 * 清洗、切分后的景点指南知识块（纯数据）。
 *
 * @param chunkId 块 ID（景点 ID，长文本切分时追加分段序号）
 * @param city    城市
 * @param guideId 景点原始 ID
 * @param title   景点名称
 * @param tags    标签
 * @param rating  评分
 * @param isHot   是否热门
 * @param text    清洗后的文本（用于嵌入与检索展示，含标题与城市前缀）
 */
public record GuideChunk(
        String chunkId,
        String city,
        String guideId,
        String title,
        List<String> tags,
        double rating,
        boolean isHot,
        String text) {

    /** 空标签列表兜底，避免调用方拿到 null */
    public GuideChunk {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
