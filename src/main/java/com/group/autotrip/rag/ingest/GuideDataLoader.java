package com.group.autotrip.rag.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 知识源加载：读取 classpath:guides 下的城市景点指南 JSON。
 */
@Component
public class GuideDataLoader {

    private static final Logger log = LoggerFactory.getLogger(GuideDataLoader.class);

    private static final String GUIDE_PATTERN = "classpath:guides/*.json";

    /** 目录枚举失败时的兜底文件列表（防止打包后目录不可扫描） */
    private static final List<String> FALLBACK_FILES = List.of("大理", "杭州", "上海", "长沙");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 指南原始记录（与 guides/*.json 字段一一对应） */
    public record RawGuide(
            @JsonProperty("id") String id,
            @JsonProperty("title") String title,
            @JsonProperty("content") String content,
            @JsonProperty("city") String city,
            @JsonProperty("type") String type,
            @JsonProperty("tags") List<String> tags,
            @JsonProperty("rating") double rating,
            @JsonProperty("isHot") boolean isHot) {
    }

    /** 加载全部城市指南；文件名排序，保证构建顺序稳定 */
    public List<RawGuide> loadAll() throws IOException {
        List<Resource> files = locateFiles();
        List<RawGuide> all = new ArrayList<>();
        for (Resource file : files) {
            log.info("加载指南数据文件：{}", file.getFilename());
            try (InputStream in = file.getInputStream()) {
                RawGuide[] guides = mapper.readValue(in, RawGuide[].class);
                for (RawGuide guide : guides) {
                    all.add(guide);
                }
            }
        }
        return all;
    }

    private List<Resource> locateFiles() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<Resource> files = new ArrayList<>(List.of(resolver.getResources(GUIDE_PATTERN)));
        if (!files.isEmpty()) {
            files.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            return files;
        }
        for (String city : FALLBACK_FILES) {
            Resource resource = resolver.getResource("classpath:guides/" + city + ".json");
            if (resource.exists()) {
                files.add(resource);
            }
        }
        if (files.isEmpty()) {
            throw new IOException("未找到指南数据文件：" + GUIDE_PATTERN);
        }
        return files;
    }
}
