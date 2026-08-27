package com.group.autotrip.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideDataLoaderTest {

    @Test
    void loadsAllCityGuidesFromClasspath() throws Exception {
        List<GuideDataLoader.RawGuide> guides = new GuideDataLoader().loadAll();

        assertFalse(guides.isEmpty(), "应能加载到指南数据");
        Set<String> cities = guides.stream()
                .map(GuideDataLoader.RawGuide::city)
                .collect(Collectors.toSet());
        assertTrue(cities.containsAll(Set.of("大理", "杭州", "上海", "长沙")),
                "四个城市指南都应加载到，实际：" + cities);
        for (GuideDataLoader.RawGuide guide : guides) {
            assertTrue(guide.id() != null && !guide.id().isBlank());
            assertTrue(guide.title() != null && !guide.title().isBlank());
            assertTrue(guide.content() != null && !guide.content().isBlank());
            assertTrue(guide.rating() > 0);
        }
    }
}
