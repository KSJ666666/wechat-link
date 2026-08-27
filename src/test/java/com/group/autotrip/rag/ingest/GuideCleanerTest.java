package com.group.autotrip.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideCleanerTest {

    @Test
    void parsesSemiStructuredContent() {
        Map<String, String> fields = GuideCleaner.parse(
                "类型:风景名胜;风景名胜;国家级景点；地址:千岛湖镇；电话:0571-64816244；评分:4.8；开放时间:周一至周日 08:00-15:00");
        assertEquals("风景名胜、风景名胜、国家级景点", fields.get("类型"));
        assertEquals("千岛湖镇", fields.get("地址"));
        assertEquals("0571-64816244", fields.get("电话"));
        assertEquals("周一至周日08:00-15:00", fields.get("开放时间"));
        assertFalse(fields.containsKey("附加说明"));    }

    @Test
    void keylessSegmentsBecomeExtraNotes() {
        Map<String, String> fields = GuideCleaner.parse(
                "类型:风景名胜；地址:某地；（一）开闭园时间 夏令时:4月1日-10月7日");
        assertEquals("某地", fields.get("地址"));
        assertTrue(fields.get("附加说明").contains("开闭园时间"));
    }

    @Test
    void buildsCleanBodyWithoutPhone() {
        GuideDataLoader.RawGuide raw = new GuideDataLoader.RawGuide(
                "amap-1", "西湖", "类型:风景名胜；地址:龙井路1号；电话:暂无；评分:4.9；开放时间:00:00-24:00",
                "杭州", "ATTRACTION", List.of("景点", "杭州", "风景"), 4.9, true);
        GuideCleaner.CleanedGuide cleaned = new GuideCleaner().clean(raw);
        assertEquals("西湖", cleaned.title());
        assertEquals("杭州", cleaned.city());
        assertTrue(cleaned.body().contains("类型"));
        assertTrue(cleaned.body().contains("开放时间"));
        assertFalse(cleaned.body().contains("电话"));
    }

    @Test
    void normalizesPunctuationAndWhitespace() {
        assertEquals("abc", GuideCleaner.normalize("  a b c  "));
        assertEquals("风景、名胜", GuideCleaner.normalize("风景;名胜"));
        assertEquals("时间:08:30", GuideCleaner.normalize("时间:08:30"));
    }
}
