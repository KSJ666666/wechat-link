package com.group.autotrip.rag.retrieve;

import com.group.autotrip.rag.model.GuideChunk;
import com.group.autotrip.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPromptBuilderTest {

    @Test
    void buildsPromptWithSourcesAndQuery() {
        GuideChunk chunk = new GuideChunk(
                "c1", "杭州", "c1", "杭州西湖",
                List.of("景点", "杭州", "风景"), 4.9, true,
                "景点名称：杭州西湖，城市：杭州，类型：风景名胜，开放时间：全天");
        RagPromptBuilder builder = new RagPromptBuilder();

        String prompt = builder.build("杭州有什么好玩的", List.of(new ScoredChunk(chunk, 0.9, "VECTOR")));

        assertTrue(prompt.contains("参考资料"));
        assertTrue(prompt.contains("杭州西湖"));
        assertTrue(prompt.contains("杭州有什么好玩的"));
        assertTrue(prompt.contains("评分 4.9"));
        assertTrue(prompt.contains("热门"));
    }
}
