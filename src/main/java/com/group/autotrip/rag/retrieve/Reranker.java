package com.group.autotrip.rag.retrieve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.rag.model.ScoredChunk;
import com.group.autotrip.rag.store.VsmKeywordIndex;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ⑦ 重排：优先调用阿里云百炼 gte-rerank-v2 重排模型，失败或未配置 Key 时
 * 自动降级为本地规则重排（标题匹配 + 标签匹配 + 评分 + 热门 + 检索分）。
 */
@Component
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    private static final String RERANK_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final String apiKey;
    private final String model;

    public Reranker(
            @Value("${rag.rerank.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${rag.rerank.model:gte-rerank-v2}") String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    /** 对候选重排并取 topK；候选不超过 topK 时直接返回原顺序 */
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() <= topK) {
            return new ArrayList<>(candidates);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return rerankLocally(query, candidates, topK);
        }
        try {
            return rerankByApi(query, candidates, topK);
        } catch (Exception e) {
            log.warn("重排模型调用失败，降级本地规则重排：{}", e.getMessage());
            return rerankLocally(query, candidates, topK);
        }
    }

    private List<ScoredChunk> rerankByApi(String query, List<ScoredChunk> candidates, int topK)
            throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        input.put("query", query);
        ArrayNode documents = input.putArray("documents");
        for (ScoredChunk c : candidates) {
            documents.add(c.chunk().text());
        }
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("top_n", Math.max(1, Math.min(topK, candidates.size())));
        parameters.put("return_documents", false);

        Request request = new Request.Builder()
                .url(RERANK_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + text);
            }
            JsonNode results = mapper.readTree(text).path("output").path("results");
            List<ScoredChunk> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                double score = result.path("relevance_score").asDouble(0);
                if (index >= 0 && index < candidates.size()) {
                    ScoredChunk c = candidates.get(index);
                    reranked.add(new ScoredChunk(c.chunk(), score, c.source()));
                }
            }
            reranked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
            return reranked;
        }
    }

    /** 本地规则重排（包内可见，便于单元测试） */
    List<ScoredChunk> rerankLocally(String query, List<ScoredChunk> candidates, int topK) {
        List<String> queryTokens = VsmKeywordIndex.tokens(query);
        double maxRetrievalScore = candidates.stream()
                .mapToDouble(ScoredChunk::score).max().orElse(1.0);
        List<ScoredChunk> scored = new ArrayList<>(candidates.size());
        for (ScoredChunk c : candidates) {
            double titleMatch = coverage(queryTokens, c.chunk().title());
            double tagMatch = 0;
            for (String tag : c.chunk().tags()) {
                if (!tag.isBlank() && query.contains(tag)) {
                    tagMatch = 1;
                    break;
                }
            }
            double rating = Math.max(0, c.chunk().rating()) / 5.0;
            double hot = c.chunk().isHot() ? 1 : 0;
            double retrieval = maxRetrievalScore > 0 ? c.score() / maxRetrievalScore : 0;
            double score = 0.4 * titleMatch + 0.2 * tagMatch + 0.2 * rating
                    + 0.1 * hot + 0.1 * retrieval;
            scored.add(new ScoredChunk(c.chunk(), score, c.source()));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(h -> h.chunk().chunkId()));
        return scored.size() > topK ? new ArrayList<>(scored.subList(0, topK)) : scored;
    }

    /** query bigram 覆盖标题的比例 */
    private static double coverage(List<String> queryTokens, String title) {
        if (queryTokens.isEmpty() || title == null || title.isBlank()) {
            return 0;
        }
        int hit = 0;
        for (String token : queryTokens) {
            if (title.contains(token)) {
                hit++;
            }
        }
        return (double) hit / queryTokens.size();
    }
}
