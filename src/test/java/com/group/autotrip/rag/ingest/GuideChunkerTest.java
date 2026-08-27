package com.group.autotrip.rag.ingest;

import com.group.autotrip.rag.model.GuideChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideChunkerTest {

    @Test
    void shortBodyProducesSingleChunkWithHeader() {
        GuideChunk chunk = new GuideChunker().chunk(cleaned(
                "amap-1", "西湖", "杭州", "类型：风景名胜，地址：龙井路1号")).get(0);
        assertEquals("amap-1", chunk.chunkId());
        assertTrue(chunk.text().startsWith("景点名称：西湖，城市：杭州，"));
        assertTrue(chunk.text().contains("类型"));
    }

    @Test
    void longBodySplitsWithHeaderOnEveryPiece() {
        GuideChunker chunker = new GuideChunker(50, 10);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            body.append("这是第").append(i).append("段说明文字，");
        }
        List<GuideChunk> chunks = chunker.chunk(cleaned("amap-2", "景点X", "上海", body.toString()));

        assertTrue(chunks.size() > 1);
        assertEquals("amap-2#1", chunks.get(0).chunkId());
        assertEquals("amap-2#2", chunks.get(1).chunkId());
        for (GuideChunk chunk : chunks) {
            assertTrue(chunk.text().startsWith("景点名称：景点X，城市：上海，"));
        }
    }

    @Test
    void piecesStayWithinMaxChars() {
        GuideChunker chunker = new GuideChunker(50, 10);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            body.append("这是第").append(i).append("段说明文字，");
        }
        for (String piece : chunker.split(body.toString())) {
            assertTrue(piece.length() <= 50, "分段长度超限: " + piece.length());
        }
    }

    private static GuideCleaner.CleanedGuide cleaned(
            String id, String title, String city, String body) {
        return new GuideCleaner.CleanedGuide(
                id, city, title, List.of("景点", city, "风景"), 4.8, true,
                "风景名胜", "", "", "", body);
    }
}
