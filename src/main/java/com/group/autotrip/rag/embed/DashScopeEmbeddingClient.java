package com.group.autotrip.rag.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * ③⑤ 向量化：调用阿里云百炼 text-embedding-v3（OpenAI 兼容端点 compatible-mode/v1/embeddings）。
 * API Key 复用环境变量 DASHSCOPE_API_KEY，也可用 rag.embedding.api-key 单独配置。
 */
@Component
public class DashScopeEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(DashScopeEmbeddingClient.class);

    private static final String EMBEDDINGS_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final int batchSize;

    public DashScopeEmbeddingClient(
            @Value("${rag.embedding.api-key:${DASHSCOPE_API_KEY:}}") String apiKey,
            @Value("${rag.embedding.model:text-embedding-v3}") String model,
            @Value("${rag.embedding.dimensions:1024}") int dimensions,
            @Value("${rag.embedding.batch-size:10}") int batchSize) {
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
    }

    /** 是否配置了 API Key（未配置时调用方应跳过向量化） */
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String model() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    /** 批量向量化，按输入顺序返回等长向量列表 */
    public List<float[]> embed(List<String> texts) throws IOException {
        requireKey();
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(from + batchSize, texts.size()));
            result.addAll(embedBatch(batch));
        }
        return result;
    }

    /** 单条向量化 */
    public float[] embedOne(String text) throws IOException {
        return embed(List.of(text)).get(0);
    }

    private List<float[]> embedBatch(List<String> texts) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        for (String text : texts) {
            input.add(text);
        }
        if (dimensions > 0) {
            body.put("dimensions", dimensions);
        }
        JsonNode resp = postWithRetry(body, 1);
        ArrayNode data = (ArrayNode) resp.path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IOException("嵌入接口返回条数不符: " + data);
        }
        List<float[]> vectors = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            ArrayNode embedding = (ArrayNode) item.path("embedding");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private JsonNode postWithRetry(ObjectNode body, int attempt) throws IOException {
        Request request = new Request.Builder()
                .url(EMBEDDINGS_URL)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(body.toString(),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            // 限流（429）时退避重试一次
            if (response.code() == 429 && attempt < 2) {
                log.warn("嵌入接口限流（429），1.5 秒后重试");
                sleepQuietly(1500);
                return postWithRetry(body, attempt + 1);
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + truncate(text));
            }
            return mapper.readTree(text);
        }
    }

    private void requireKey() {
        if (!available()) {
            throw new IllegalStateException("未配置阿里云百炼 API Key：请检查环境变量 DASHSCOPE_API_KEY，"
                    + "或在 application.properties 中设置 rag.embedding.api-key");
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
