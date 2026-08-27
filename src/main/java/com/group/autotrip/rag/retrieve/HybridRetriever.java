package com.group.autotrip.rag.retrieve;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import com.group.autotrip.rag.store.MilvusVectorStore;
import com.group.autotrip.rag.store.VsmKeywordIndex;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ⑥ 混合检索：向量（Milvus）+ 关键词（VSM）两路召回，RRF（Reciprocal Rank Fusion）融合，
 * 支持城市过滤。任一路不可用时自动退化为单路，不抛异常。
 */
@Component
public class HybridRetriever {

    /** RRF 常数 k，经典取 60 */
    static final double RRF_K = 60.0;

    private final MilvusVectorStore milvus;
    private final VsmKeywordIndex vsm;

    public HybridRetriever(MilvusVectorStore milvus, VsmKeywordIndex vsm) {
        this.milvus = milvus;
        this.vsm = vsm;
    }

    /**
     * 两路检索并 RRF 融合。
     *
     * @param queryVector query 向量，可为 null（向量一路跳过）
     * @param city        城市过滤，空字符串表示不过滤
     * @param candidates  每路召回数与融合后的上限
     * @return 融合得分降序的候选列表
     */
    public List<ScoredChunk> retrieve(String query, float[] queryVector, String city, int candidates) {
        List<ScoredChunk> vectorHits = milvus.search(queryVector, candidates, city);
        List<ScoredChunk> keywordHits = vsm.search(query, candidates, city);

        Map<String, Fused> fused = new LinkedHashMap<>();
        addPath(fused, vectorHits, "VECTOR");
        addPath(fused, keywordHits, "KEYWORD");

        return fused.values().stream()
                .map(f -> new ScoredChunk(f.chunk, f.rrfScore, String.join("+", f.sources)))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(h -> h.chunk().chunkId()))
                .limit(Math.max(1, candidates))
                .toList();
    }

    private void addPath(Map<String, Fused> fused, List<ScoredChunk> hits, String source) {
        int rank = 1;
        for (ScoredChunk hit : hits) {
            Fused f = fused.computeIfAbsent(hit.chunk().chunkId(), id -> new Fused(hit.chunk()));
            f.rrfScore += 1.0 / (RRF_K + rank);
            if (!f.sources.contains(source)) {
                f.sources.add(source);
            }
            rank++;
        }
    }

    private static final class Fused {
        final GuideChunk chunk;
        double rrfScore;
        final List<String> sources = new ArrayList<>(2);

        Fused(GuideChunk chunk) {
            this.chunk = chunk;
        }
    }
}
