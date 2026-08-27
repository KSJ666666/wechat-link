package com.group.autotrip.rag.ingest;

import com.group.autotrip.rag.model.GuideChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ② Chunking：默认 1 个景点 1 个知识块；清洗后正文超过 maxChars 时按标点边界切分，
 * 相邻块之间保留 overlap 个字符的重叠。每个块都带"景点名称 + 城市"前缀，保证向量语义完整。
 */
@Component
public class GuideChunker {

    public static final int DEFAULT_MAX_CHARS = 400;
    public static final int DEFAULT_OVERLAP = 50;

    /** 优先在哪些字符处断句 */
    private static final String BOUNDARIES = "，。！？；、";

    private final int maxChars;
    private final int overlap;

    public GuideChunker() {
        this(DEFAULT_MAX_CHARS, DEFAULT_OVERLAP);
    }

    public GuideChunker(int maxChars, int overlap) {
        this.maxChars = maxChars;
        this.overlap = overlap;
    }

    /** 把一个景点切成 1 个或多个知识块 */
    public List<GuideChunk> chunk(GuideCleaner.CleanedGuide guide) {
        String header = "景点名称：" + guide.title() + "，城市：" + guide.city() + "，";
        List<String> pieces = split(guide.body());
        List<GuideChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            String chunkId = pieces.size() == 1 ? guide.guideId() : guide.guideId() + "#" + (i + 1);
            chunks.add(new GuideChunk(
                    chunkId, guide.city(), guide.guideId(), guide.title(),
                    guide.tags(), guide.rating(), guide.isHot(), header + pieces.get(i)));
        }
        return chunks;
    }

    /** 超长正文按标点边界切分，带 overlap 重叠；短正文原样返回 */
    List<String> split(String body) {
        if (body == null || body.isBlank()) {
            return List.of("");
        }
        if (body.length() <= maxChars) {
            return List.of(body);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < body.length()) {
            int end = Math.min(start + maxChars, body.length());
            if (end < body.length()) {
                int boundary = lastBoundary(body, end, start + maxChars / 2);
                if (boundary > start) {
                    end = boundary + 1;
                }
            }
            pieces.add(body.substring(start, end));
            if (end >= body.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return pieces;
    }

    /** 在 [min, end) 区间内找最后一个断句字符位置，找不到返回 -1 */
    private static int lastBoundary(String text, int end, int min) {
        for (int i = end - 1; i >= min; i--) {
            if (BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
