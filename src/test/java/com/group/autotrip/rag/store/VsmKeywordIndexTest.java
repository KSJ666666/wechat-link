package com.group.autotrip.rag.store;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VsmKeywordIndexTest {

    private static List<GuideChunk> sampleChunks() {
        return List.of(
                chunk("c1", "杭州西湖", "杭州", "类型：风景名胜，地址：龙井路1号，开放时间：全天"),
                chunk("c2", "长沙橘子洲", "长沙", "类型：公园，地址：岳麓区，开放时间：全天"),
                chunk("c3", "大理古城", "大理", "类型：古镇，地址：大理市，开放时间：全天"));
    }

    @Test
    void searchRanksMatchingChunkFirst() {
        VsmKeywordIndex index = new VsmKeywordIndex();
        index.build(sampleChunks());

        List<ScoredChunk> hits = index.search("西湖怎么去", 3, null);

        assertFalse(hits.isEmpty());
        assertEquals("杭州西湖", hits.get(0).chunk().title());
        assertEquals("KEYWORD", hits.get(0).source());
        assertTrue(index.isBuilt());
        assertEquals(3, index.size());
    }

    @Test
    void cityFilterKeepsOnlyTargetCity() {
        VsmKeywordIndex index = new VsmKeywordIndex();
        index.build(sampleChunks());

        List<ScoredChunk> hits = index.search("长沙有哪些公园", 5, "长沙");

        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().allMatch(h -> h.chunk().city().equals("长沙")));
    }

    @Test
    void blankOrSingleCharQueryYieldsNoResults() {
        VsmKeywordIndex index = new VsmKeywordIndex();
        index.build(sampleChunks());

        assertTrue(index.search("", 5, null).isEmpty());
        assertTrue(index.search("一", 5, null).isEmpty());
    }

    private static GuideChunk chunk(String id, String title, String city, String text) {
        return new GuideChunk(
                id, city, id, title, List.of("景点", city, "风景"), 4.8, true, text);
    }
}
