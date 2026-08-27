package com.group.autotrip.rag.store;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ④ 向量库：Milvus standalone 2.4 的向量存取（懒连接，任何异常只降级不抛出）。
 *
 * <p>集合 schema：id(Int64 自增主键)、chunk_id、city、guide_id、title、tags、rating、is_hot、
 * text（VARCHAR 1024）、vector（FLOAT_VECTOR，HNSW + COSINE 索引）。
 */
@Component
public class MilvusVectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    public static final String VECTOR_FIELD = "vector";

    private final String host;
    private final int port;
    private final String collection;
    private final int dimension;

    private volatile MilvusServiceClient client;

    public MilvusVectorStore(
            @Value("${rag.milvus.host:localhost}") String host,
            @Value("${rag.milvus.port:19530}") int port,
            @Value("${rag.milvus.collection:trip_guide_chunks}") String collection,
            @Value("${rag.embedding.dimensions:1024}") int dimension) {
        this.host = host;
        this.port = port;
        this.collection = collection;
        this.dimension = dimension;
    }

    public String collection() {
        return collection;
    }

    /** Milvus 是否可达（懒连接 + hasCollection 探测，任何异常视为不可用） */
    public boolean available() {
        try {
            R<Boolean> resp = connect().hasCollection(
                    HasCollectionParam.newBuilder().withCollectionName(collection).build());
            return resp.getStatus() == 0;
        } catch (Exception e) {
            log.debug("Milvus 不可用：{}", e.getMessage());
            return false;
        }
    }

    /** 重建集合（删除旧集合 → 建集合 → 建索引 → 加载），随后写入向量 */
    public synchronized long recreateAndInsert(List<GuideChunk> chunks, List<float[]> vectors) {
        recreateCollection();
        return insert(chunks, vectors);
    }

    private synchronized void recreateCollection() {
        MilvusServiceClient c = connect();
        R<Boolean> has = c.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collection).build());
        check(has);
        if (Boolean.TRUE.equals(has.getData())) {
            R<RpcStatus> drop = c.dropCollection(
                    DropCollectionParam.newBuilder().withCollectionName(collection).build());
            check(drop);
            log.info("已删除旧集合 {}", collection);
        }
        createCollection(c);
        createIndexAndLoad(c);
    }

    private void createCollection(MilvusServiceClient c) {
        FieldType id = FieldType.newBuilder()
                .withName("id").withDataType(DataType.Int64)
                .withPrimaryKey(true).withAutoID(true)
                .build();
        List<FieldType> fields = List.of(
                id,
                varChar("chunk_id", 64),
                varChar("city", 16),
                varChar("guide_id", 64),
                varChar("title", 128),
                varChar("tags", 128),
                FieldType.newBuilder().withName("rating").withDataType(DataType.Float).build(),
                FieldType.newBuilder().withName("is_hot").withDataType(DataType.Bool).build(),
                varChar("text", 1024),
                FieldType.newBuilder()
                        .withName(VECTOR_FIELD).withDataType(DataType.FloatVector)
                        .withDimension(dimension)
                        .build());
        R<RpcStatus> resp = c.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(collection)
                .withFieldTypes(fields)
                .build());
        check(resp);
        log.info("已创建集合 {}（向量维度 {}）", collection, dimension);
    }

    private void createIndexAndLoad(MilvusServiceClient c) {
        R<RpcStatus> index = c.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(collection)
                .withFieldName(VECTOR_FIELD)
                .withIndexType(IndexType.HNSW)
                .withMetricType(MetricType.COSINE)
                .withExtraParam("{\"M\":16,\"efConstruction\":64}")
                .build());
        check(index);
        R<RpcStatus> load = c.loadCollection(
                LoadCollectionParam.newBuilder().withCollectionName(collection).build());
        check(load);
        log.info("已为集合 {} 创建 HNSW 索引并加载", collection);
    }

    /** 批量写入知识块及其向量（块与向量一一对应） */
    public synchronized long insert(List<GuideChunk> chunks, List<float[]> vectors) {
        if (chunks.isEmpty()) {
            return 0;
        }
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("知识块数量与向量数量不一致："
                    + chunks.size() + " vs " + vectors.size());
        }
        MilvusServiceClient c = connect();
        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("chunk_id", chunks.stream().map(GuideChunk::chunkId).toList()));
        fields.add(new InsertParam.Field("city", chunks.stream().map(GuideChunk::city).toList()));
        fields.add(new InsertParam.Field("guide_id", chunks.stream().map(GuideChunk::guideId).toList()));
        fields.add(new InsertParam.Field("title", chunks.stream().map(GuideChunk::title).toList()));
        fields.add(new InsertParam.Field("tags",
                chunks.stream().map(g -> String.join(",", g.tags())).toList()));
        fields.add(new InsertParam.Field("rating",
                chunks.stream().map(g -> (float) g.rating()).toList()));
        fields.add(new InsertParam.Field("is_hot", chunks.stream().map(GuideChunk::isHot).toList()));
        fields.add(new InsertParam.Field("text", chunks.stream().map(GuideChunk::text).toList()));
        List<List<Float>> vectorRows = new ArrayList<>(vectors.size());
        for (float[] vector : vectors) {
            vectorRows.add(toFloatList(vector));
        }
        fields.add(new InsertParam.Field(VECTOR_FIELD, vectorRows));
        R<MutationResult> resp = c.insert(InsertParam.newBuilder()
                .withCollectionName(collection)
                .withFields(fields)
                .build());
        check(resp);
        long count = resp.getData().getInsertCnt();
        log.info("已向集合 {} 写入 {} 条向量", collection, count);
        return count;
    }

    /**
     * 向量检索：topK 条，city 非空时加标量过滤。任何异常（含 Milvus 不可用）返回空列表，
     * 由上层降级为关键词检索。
     */
    public List<ScoredChunk> search(float[] queryVector, int topK, String city) {
        try {
            if (queryVector == null || queryVector.length == 0 || !available()) {
                return List.of();
            }
            SearchParam.Builder builder = SearchParam.newBuilder()
                    .withCollectionName(collection)
                    .withMetricType(MetricType.COSINE)
                    .withVectorFieldName(VECTOR_FIELD)
                    .withTopK(topK)
                    .withVectors(List.of(toFloatList(queryVector)))
                    .withParams("{\"ef\":128}")
                    .withConsistencyLevel(ConsistencyLevelEnum.STRONG)
                    .withOutFields(List.of("chunk_id", "city", "guide_id", "title",
                            "tags", "rating", "is_hot", "text"));
            if (city != null && !city.isBlank()) {
                builder.withExpr("city == \"" + city.trim() + "\"");
            }
            R<SearchResults> resp = connect().search(builder.build());
            check(resp);
            if (resp.getData() == null || resp.getData().getResults() == null) {
                return List.of();
            }
            SearchResultsWrapper wrapper = new SearchResultsWrapper(resp.getData().getResults());
            List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
            List<ScoredChunk> hits = new ArrayList<>(scores.size());
            for (SearchResultsWrapper.IDScore score : scores) {
                hits.add(toScoredChunk(score));
            }
            return hits;
        } catch (Exception e) {
            log.warn("Milvus 向量检索失败，返回空结果（将降级关键词检索）：{}", e.getMessage());
            return List.of();
        }
    }

    private ScoredChunk toScoredChunk(SearchResultsWrapper.IDScore score) {
        String tagsText = str(score, "tags");
        List<String> tags = tagsText.isBlank() ? List.of() : Arrays.asList(tagsText.split(","));
        Object ratingObj = score.get("rating");
        double rating = ratingObj instanceof Number n ? n.doubleValue() : 0.0;
        boolean isHot = Boolean.TRUE.equals(score.get("is_hot"));
        GuideChunk chunk = new GuideChunk(
                str(score, "chunk_id"),
                str(score, "city"),
                str(score, "guide_id"),
                str(score, "title"),
                tags,
                rating,
                isHot,
                str(score, "text"));
        return new ScoredChunk(chunk, score.getScore(), "VECTOR");
    }

    private static String str(SearchResultsWrapper.IDScore score, String field) {
        Object value = score.get(field);
        return value == null || "null".equals(String.valueOf(value)) ? "" : String.valueOf(value);
    }

    private synchronized MilvusServiceClient connect() {
        if (client == null) {
            client = new MilvusServiceClient(ConnectParam.newBuilder()
                    .withHost(host)
                    .withPort(port)
                    .build());
            log.info("已连接 Milvus：{}:{}", host, port);
        }
        return client;
    }

    private static void check(R<?> resp) {
        if (resp.getStatus() != 0) {
            throw new IllegalStateException(
                    "Milvus 操作失败(code=" + resp.getStatus() + "): " + resp.getMessage());
        }
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> list = new ArrayList<>(values.length);
        for (float value : values) {
            list.add(value);
        }
        return list;
    }

    private static FieldType varChar(String name, int maxLength) {
        return FieldType.newBuilder()
                .withName(name).withDataType(DataType.VarChar)
                .withMaxLength(maxLength)
                .build();
    }

    @PreDestroy
    public void close() {
        MilvusServiceClient c = client;
        if (c != null) {
            try {
                c.close(3);
                log.info("Milvus 客户端已关闭");
            } catch (Exception e) {
                log.debug("关闭 Milvus 客户端失败：{}", e.getMessage());
            }
        }
    }
}
