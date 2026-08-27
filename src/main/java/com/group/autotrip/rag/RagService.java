package com.group.autotrip.rag;

import com.group.autotrip.agent.DashScopeService;
import com.group.autotrip.rag.embed.DashScopeEmbeddingClient;
import com.group.autotrip.rag.model.RagAnswer;
import com.group.autotrip.rag.model.ScoredChunk;
import com.group.autotrip.rag.retrieve.HybridRetriever;
import com.group.autotrip.rag.retrieve.RagPromptBuilder;
import com.group.autotrip.rag.retrieve.Reranker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 问答编排：⑤ query 向量化 → ⑥ 混合检索（向量 + 关键词 + 过滤）→ ⑦ 重排
 * → ⑧ 增强 Prompt → ⑨ LLM 生成答案。
 *
 * <p>复用现有 {@link DashScopeService} 生成答案（仅注入调用，不修改 agent 包）。
 * 各外部依赖失败时逐级降级：向量化失败 → 仅关键词检索；LLM 失败 → 直接罗列检索资料。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final DashScopeEmbeddingClient embeddingClient;
    private final HybridRetriever retriever;
    private final Reranker reranker;
    private final RagPromptBuilder promptBuilder;
    private final DashScopeService llm;
    private final RagIndexer indexer;

    @Value("${rag.retrieve.candidates:20}")
    private int candidates;

    @Value("${rag.retrieve.top-k:5}")
    private int topK;

    public RagService(DashScopeEmbeddingClient embeddingClient, HybridRetriever retriever,
                      Reranker reranker, RagPromptBuilder promptBuilder,
                      DashScopeService llm, RagIndexer indexer) {
        this.embeddingClient = embeddingClient;
        this.retriever = retriever;
        this.reranker = reranker;
        this.promptBuilder = promptBuilder;
        this.llm = llm;
        this.indexer = indexer;
    }

    /** 执行一次 RAG 问答；外部依赖异常时尽量降级而不是抛出 */
    public RagAnswer ask(String query, String city) {
        indexer.ensureBuilt();

        float[] queryVector = null;
        if (embeddingClient.available()) {
            try {
                queryVector = embeddingClient.embedOne(query);
            } catch (Exception e) {
                log.warn("query 向量化失败，本次仅关键词检索：{}", e.getMessage());
            }
        }

        List<ScoredChunk> hits = retriever.retrieve(query, queryVector, city, candidates);
        if (hits.isEmpty()) {
            return new RagAnswer(
                    "抱歉，知识库中没有找到与“" + query + "”相关的景点资料。", List.of());
        }

        List<ScoredChunk> reranked = reranker.rerank(query, hits, topK);
        String prompt = promptBuilder.build(query, reranked);
        try {
            return new RagAnswer(llm.chat(prompt), reranked);
        } catch (Exception e) {
            log.warn("LLM 生成失败，退回纯检索结果：{}", e.getMessage());
            return new RagAnswer(fallbackAnswer(query, reranked), reranked);
        }
    }

    /** LLM 不可用时的兜底回答：直接罗列检索到的资料 */
    private String fallbackAnswer(String query, List<ScoredChunk> reranked) {
        StringBuilder sb = new StringBuilder("关于“").append(query).append("”，知识库检索到以下资料：\n");
        int index = 1;
        for (ScoredChunk source : reranked) {
            sb.append(index++).append(". ")
                    .append(source.chunk().title())
                    .append("（").append(source.chunk().city()).append("）：")
                    .append(source.chunk().text()).append('\n');
        }
        return sb.toString();
    }
}
