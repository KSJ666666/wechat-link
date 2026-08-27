package com.group.autotrip.rag;

import com.group.autotrip.rag.model.RagAnswer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * RAG 自测与维护接口（调试用，全部挂在 /rag 下，不影响微信接口）。
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    private final RagService ragService;
    private final RagIndexer ragIndexer;

    public RagController(RagService ragService, RagIndexer ragIndexer) {
        this.ragService = ragService;
        this.ragIndexer = ragIndexer;
    }

    /** 索引与依赖状态 */
    @GetMapping("/status")
    public RagIndexer.Status status() {
        return ragIndexer.status();
    }

    /** 重建索引：清洗 → 切分 → 向量化 → Milvus + VSM */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        try {
            ragIndexer.build();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "chunkCount", ragIndexer.status().chunkCount()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", String.valueOf(e.getMessage())));
        }
    }

    /** RAG 问答自测，请求体 {"query":"...", "city":"杭州"} */
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        String query = body.get("query");
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "query 不能为空"));
        }
        try {
            RagAnswer answer = ragService.ask(query, body.getOrDefault("city", ""));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", answer.answer(),
                    "sources", answer.sourceTitles()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", String.valueOf(e.getMessage())));
        }
    }
}
