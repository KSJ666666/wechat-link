package com.group.autotrip.rag;

import com.group.autotrip.rag.embed.DashScopeEmbeddingClient;
import com.group.autotrip.rag.ingest.GuideChunker;
import com.group.autotrip.rag.ingest.GuideCleaner;
import com.group.autotrip.rag.ingest.GuideDataLoader;
import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.store.MilvusVectorStore;
import com.group.autotrip.rag.store.VsmKeywordIndex;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 建库编排：① 清洗 → ② 切分 → ③ 向量化 → ④ 写入 Milvus 向量库与内存 VSM 关键词索引。
 *
 * <p>关键词索引不依赖任何外部服务，永远最先构建；向量索引（嵌入 + Milvus）失败时只告警，
 * 查询自动降级为关键词检索，不影响主流程。
 */
@Component
public class RagIndexer {

    private static final Logger log = LoggerFactory.getLogger(RagIndexer.class);

    private final GuideDataLoader loader;
    private final GuideCleaner cleaner;
    private final GuideChunker chunker;
    private final DashScopeEmbeddingClient embeddingClient;
    private final MilvusVectorStore milvus;
    private final VsmKeywordIndex vsm;

    @Value("${rag.index.auto-build:true}")
    private boolean autoBuild;

    private volatile long builtAtMillis;
    private volatile int chunkCount;
    private volatile String lastError = "";

    public RagIndexer(GuideDataLoader loader, GuideCleaner cleaner, GuideChunker chunker,
                      DashScopeEmbeddingClient embeddingClient,
                      MilvusVectorStore milvus, VsmKeywordIndex vsm) {
        this.loader = loader;
        this.cleaner = cleaner;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.milvus = milvus;
        this.vsm = vsm;
    }

    /** 启动时自动构建索引；任何失败只告警，不阻断应用启动 */
    @PostConstruct
    void autoBuild() {
        if (!autoBuild) {
            log.info("RAG 启动自动建索引已关闭（rag.index.auto-build=false）");
            return;
        }
        try {
            build();
            log.info("启动时 RAG 索引构建完成：{} 个知识块", chunkCount);
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("启动时 RAG 索引构建失败（不影响其他功能）：{}", e.getMessage());
        }
    }

    /** 查询前确保关键词索引存在：未构建过则现场构建一次 */
    public void ensureBuilt() {
        if (vsm.isBuilt()) {
            return;
        }
        synchronized (this) {
            if (vsm.isBuilt()) {
                return;
            }
            try {
                build();
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("RAG 索引构建失败：{}", e.getMessage());
            }
        }
    }

    /** 完整构建：VSM 关键词索引必建；嵌入 + Milvus 尽力而为，失败记录到 lastError */
    public synchronized void build() throws IOException {
        long start = System.currentTimeMillis();
        List<GuideDataLoader.RawGuide> raw = loader.loadAll();
        List<GuideChunk> chunks = new ArrayList<>();
        for (GuideDataLoader.RawGuide guide : raw) {
            chunks.addAll(chunker.chunk(cleaner.clean(guide)));
        }
        log.info("清洗切分完成：{} 条景点 → {} 个知识块", raw.size(), chunks.size());

        vsm.build(chunks);
        chunkCount = chunks.size();

        String error = "";
        try {
            List<float[]> vectors = embeddingClient.embed(
                    chunks.stream().map(GuideChunk::text).toList());
            long inserted = milvus.recreateAndInsert(chunks, vectors);
            log.info("RAG 向量索引构建完成：Milvus 写入 {} 条，总耗时 {} ms",
                    inserted, System.currentTimeMillis() - start);
        } catch (Exception e) {
            error = e.getMessage();
            log.warn("向量索引构建失败（关键词检索仍可用）：{}", e.getMessage());
        }
        builtAtMillis = System.currentTimeMillis();
        lastError = error;
    }

    /** 当前索引与依赖状态快照 */
    public Status status() {
        return new Status(autoBuild, chunkCount, builtAtMillis,
                vsm.isBuilt(), milvus.available(), lastError,
                embeddingClient.available(), embeddingClient.model(), embeddingClient.dimensions());
    }

    /** 索引状态（纯数据） */
    public record Status(
            boolean autoBuild,
            int chunkCount,
            long builtAtMillis,
            boolean vsmBuilt,
            boolean milvusAvailable,
            String lastError,
            boolean embeddingAvailable,
            String embeddingModel,
            int embeddingDimensions) {
    }
}
