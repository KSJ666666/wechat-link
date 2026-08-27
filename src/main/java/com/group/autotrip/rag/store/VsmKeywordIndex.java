package com.group.autotrip.rag.store;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ④ 内存 VSM 关键词索引：中文字符 bigram 分词 + TF-IDF 加权 + cosine 相似度，
 * 作为混合检索中的"关键词"一路。语料只有几十条，全内存线性扫描即可毫秒级出结果。
 *
 * <p>标题、标签、城市字段的 bigram 会以更高权重入索引，突出结构化字段的区分度。
 */
@Component
public class VsmKeywordIndex {

    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private volatile int docCount;

    private record Doc(GuideChunk chunk, Map<String, Double> weights, double norm) {
    }

    /** 用知识块构建索引（全量替换） */
    public synchronized void build(List<GuideChunk> chunks) {
        Map<String, Integer> df = new HashMap<>();
        List<Map<String, Integer>> tfs = new ArrayList<>(chunks.size());
        for (GuideChunk chunk : chunks) {
            Map<String, Integer> tf = new HashMap<>();
            addAll(tf, tokens(chunk.text()), 1);
            addAll(tf, tokens(chunk.title()), 3);
            addAll(tf, tokens(String.join("", chunk.tags())), 2);
            addAll(tf, tokens(chunk.city()), 2);
            for (String token : tf.keySet()) {
                df.merge(token, 1, Integer::sum);
            }
            tfs.add(tf);
        }
        Map<String, Doc> built = new LinkedHashMap<>();
        double n = chunks.size();
        for (int i = 0; i < chunks.size(); i++) {
            GuideChunk chunk = chunks.get(i);
            Map<String, Double> weights = new HashMap<>();
            double normSq = 0;
            for (Map.Entry<String, Integer> e : tfs.get(i).entrySet()) {
                double idf = Math.log(1 + n / (1 + df.get(e.getKey())));
                double w = e.getValue() * idf;
                weights.put(e.getKey(), w);
                normSq += w * w;
            }
            built.put(chunk.chunkId(), new Doc(chunk, weights, Math.sqrt(normSq)));
        }
        docs.clear();
        docs.putAll(built);
        docCount = chunks.size();
    }

    public int size() {
        return docCount;
    }

    public boolean isBuilt() {
        return docCount > 0;
    }

    /** 关键词检索：cosine 相似度降序取 topK；city 非空时过滤 */
    public List<ScoredChunk> search(String query, int topK, String city) {
        if (docs.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> queryTf = new HashMap<>();
        addAll(queryTf, tokens(query), 1);
        if (queryTf.isEmpty()) {
            return List.of();
        }
        double queryNorm = Math.sqrt(queryTf.values().stream()
                .mapToDouble(v -> v * (double) v).sum());

        List<ScoredChunk> hits = new ArrayList<>();
        for (Doc doc : docs.values()) {
            if (city != null && !city.isBlank() && !city.equals(doc.chunk().city())) {
                continue;
            }
            if (doc.norm() == 0) {
                continue;
            }
            double dot = 0;
            for (Map.Entry<String, Integer> e : queryTf.entrySet()) {
                Double w = doc.weights().get(e.getKey());
                if (w != null) {
                    dot += e.getValue() * w;
                }
            }
            double score = dot / (queryNorm * doc.norm());
            if (score > 0) {
                hits.add(new ScoredChunk(doc.chunk(), score, "KEYWORD"));
            }
        }
        hits.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(h -> h.chunk().chunkId()));
        return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
    }

    /** 中文字符 bigram 分词：长度 1 的文本直接作为单个 token，空文本无 token */
    public static List<String> tokens(String text) {
        if (text == null) {
            return List.of();
        }
        String t = text.replaceAll("\\s+", "");
        if (t.length() < 2) {
            return t.isEmpty() ? List.of() : List.of(t);
        }
        List<String> out = new ArrayList<>(t.length() - 1);
        for (int i = 0; i + 1 < t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    private static void addAll(Map<String, Integer> tf, List<String> tokens, int count) {
        for (String token : tokens) {
            tf.merge(token, count, Integer::sum);
        }
    }
}
