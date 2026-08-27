package com.group.autotrip.rag.retrieve;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import com.group.autotrip.rag.store.MilvusVectorStore;
import com.group.autotrip.rag.store.VsmKeywordIndex;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrieverTest {

    private static GuideChunk chunk(String id, String title, String city, String text) {
        return new GuideChunk(
                id, city, id, title, List.of("景点", city, "风景"), 4.8, true, text);
    }

    @Test
    void keywordOnlyWhenMilvusUnavailable() {
        VsmKeywordIndex vsm = new VsmKeywordIndex();
        vsm.build(List.of(
                chunk("c1", "杭州西湖", "杭州", "类型：风景名胜，地址：龙井路1号"),
                chunk("c2", "长沙橘子洲", "长沙", "类型：公园，地址：岳麓区")));
        // 指向一个不存在的集合：即使 Milvus 在线，检索也会失败并降级为空
        MilvusVectorStore milvus = new MilvusVectorStore("localhost", 19530,
                "rag_test_missing_collection", 1024);
        HybridRetriever retriever = new HybridRetriever(milvus, vsm);

        List<ScoredChunk> hits = retriever.retrieve("西湖怎么去", new float[1024], null, 10);

        assertEquals(1, hits.size());
        assertEquals("杭州西湖", hits.get(0).chunk().title());
        assertEquals("KEYWORD", hits.get(0).source());
    }

    @Test
    void rrfFusesBothPathsAndMarksSource() {
        GuideChunk c1 = chunk("c1", "杭州西湖", "杭州", "类型：风景名胜，地址：龙井路1号");
        GuideChunk c2 = chunk("c2", "长沙橘子洲", "长沙", "类型：公园，地址：岳麓区");
        VsmKeywordIndex vsm = new VsmKeywordIndex();
        vsm.build(List.of(c1, c2));
        // 向量一路固定只命中 c2，验证两路融合
        MilvusVectorStore fakeMilvus = new FakeMilvus(new ScoredChunk(c2, 0.9, "VECTOR"));
        HybridRetriever retriever = new HybridRetriever(fakeMilvus, vsm);

        List<ScoredChunk> hits = retriever.retrieve("杭州西湖和长沙橘子洲", new float[1024], null, 10);

        assertEquals(2, hits.size());
        // 两路都命中的 c2 应排第一，来源标记 VECTOR+KEYWORD
        assertEquals("长沙橘子洲", hits.get(0).chunk().title());
        assertTrue(hits.get(0).source().contains("VECTOR"));
        assertTrue(hits.get(0).source().contains("KEYWORD"));
        assertEquals("KEYWORD", hits.get(1).source());
    }

    @Test
    void cityFilterAppliesToKeywordPath() {
        VsmKeywordIndex vsm = new VsmKeywordIndex();
        vsm.build(List.of(
                chunk("c1", "杭州西湖", "杭州", "类型：风景名胜"),
                chunk("c2", "长沙橘子洲", "长沙", "类型：公园")));
        HybridRetriever retriever = new HybridRetriever(
                new FakeMilvus(new ScoredChunk[0]), vsm);

        List<ScoredChunk> hits = retriever.retrieve("景点", new float[1024], "长沙", 10);

        assertTrue(hits.stream().allMatch(h -> h.chunk().city().equals("长沙")));
    }

    private static final class FakeMilvus extends MilvusVectorStore {
        private final List<ScoredChunk> hits;

        FakeMilvus(ScoredChunk... hits) {
            super("localhost", 19530, "fake", 1024);
            this.hits = List.of(hits);
        }

        @Override
        public List<ScoredChunk> search(float[] queryVector, int topK, String city) {
            return hits;
        }
    }
}
