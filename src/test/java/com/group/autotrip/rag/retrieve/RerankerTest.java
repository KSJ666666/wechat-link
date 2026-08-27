package com.group.autotrip.rag.retrieve;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankerTest {

    private static GuideChunk chunk(String title, String city, double rating, boolean isHot) {
        return new GuideChunk(
                title + "-id", city, title + "-id", title,
                List.of("景点", city, "风景"), rating, isHot, "正文：" + title);
    }

    @Test
    void localRerankBoostsTitleAndTagMatch() {
        Reranker reranker = new Reranker("", "gte-rerank-v2");
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(chunk("长沙橘子洲", "长沙", 4.6, false), 0.9, "VECTOR"),
                new ScoredChunk(chunk("杭州西湖", "杭州", 4.9, true), 0.8, "KEYWORD"));

        List<ScoredChunk> result = reranker.rerankLocally("杭州西湖怎么去", candidates, 2);

        assertEquals("杭州西湖", result.get(0).chunk().title(),
                "标题与标签匹配的西湖应重排到最前");
    }

    @Test
    void rerankFallsBackLocallyWithoutApiKey() {
        Reranker reranker = new Reranker("", "gte-rerank-v2");
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(chunk("长沙橘子洲", "长沙", 4.6, false), 0.9, "VECTOR"),
                new ScoredChunk(chunk("杭州西湖", "杭州", 4.9, true), 0.8, "KEYWORD"),
                new ScoredChunk(chunk("大理古城", "大理", 4.8, true), 0.7, "KEYWORD"));

        List<ScoredChunk> result = reranker.rerank("杭州西湖怎么去", candidates, 2);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(h -> h.chunk().title().equals("杭州西湖")));
    }

    @Test
    void smallCandidateListReturnedAsIs() {
        Reranker reranker = new Reranker("", "gte-rerank-v2");
        List<ScoredChunk> candidates = List.of(
                new ScoredChunk(chunk("长沙橘子洲", "长沙", 4.6, false), 0.9, "VECTOR"));

        List<ScoredChunk> result = reranker.rerank("长沙橘子洲", candidates, 3);

        assertEquals(candidates, result);
    }
}
