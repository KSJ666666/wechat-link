package com.group.autotrip.rag.ingest;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ① 数据清洗：把 content 的半结构化文本（"类型:…；地址:…；电话:…；评分:…；开放时间:…"）
 * 解析成结构化字段，并生成清洗后的正文。
 */
@Component
public class GuideCleaner {

    /** content 中可识别为键的字段名，其余段落一律归入附加说明 */
    private static final Set<String> KNOWN_KEYS = Set.of("类型", "地址", "电话", "评分", "开放时间");

    /** 清洗结果（纯数据），body 为不含标题/城市的正文 */
    public record CleanedGuide(
            String guideId,
            String city,
            String title,
            List<String> tags,
            double rating,
            boolean isHot,
            String typeLabel,
            String address,
            String openTime,
            String extra,
            String body) {
    }

    /** 清洗单条指南记录 */
    public CleanedGuide clean(GuideDataLoader.RawGuide raw) {
        Map<String, String> fields = parse(raw.content());
        return new CleanedGuide(
                raw.id(), raw.city(), raw.title(), raw.tags(), raw.rating(), raw.isHot(),
                fields.getOrDefault("类型", ""),
                fields.getOrDefault("地址", ""),
                fields.getOrDefault("开放时间", ""),
                fields.getOrDefault("附加说明", ""),
                buildBody(fields));
    }

    /**
     * 半结构化文本解析：按全角"；"切段；段内首个"："（或半角":"）前的内容若是已知键，
     * 则作为键值对，否则整段归入"附加说明"。值内部的冒号（如时间 08:00）原样保留。
     */
    static Map<String, String> parse(String content) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return fields;
        }
        StringBuilder extra = new StringBuilder();
        for (String rawSegment : content.split("；")) {
            String segment = normalize(rawSegment);
            if (segment.isEmpty()) {
                continue;
            }
            int colon = segment.indexOf('：');
            if (colon < 0) {
                colon = segment.indexOf(':');
            }
            if (colon > 0 && KNOWN_KEYS.contains(segment.substring(0, colon))) {
                fields.put(segment.substring(0, colon), segment.substring(colon + 1));
            } else {
                extra.append(segment).append('；');
            }
        }
        if (!extra.isEmpty()) {
            fields.put("附加说明", extra.toString());
        }
        return fields;
    }

    /** 标点归一化 + 去多余空白：半角分号转顿号 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(';', '、')
                .replaceAll("\\s+", "")
                .trim();
    }

    /** 清洗后的正文：类型 + 地址 + 开放时间 + 附加说明，电话不进正文 */
    private String buildBody(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        append(sb, "类型", fields.get("类型"));
        append(sb, "地址", fields.get("地址"));
        append(sb, "开放时间", fields.get("开放时间"));
        append(sb, "说明", fields.get("附加说明"));
        return sb.toString();
    }

    private static void append(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append('，');
        }
        sb.append(label).append('：').append(value);
    }
}
