package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 城市交通能力档案读取器。
 *
 * <p>城市数据集中在 resources/cities-transport.json，启动时读取一次，
 * 支持中文城市名、末尾带“市”的城市名以及文件内配置的拼音别名。
 */
@Component
public class CityTransportSupport {

    private static final String DATA_FILE = "cities-transport.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, CityInfo> cities = new LinkedHashMap<>();

    public CityTransportSupport() {
        load();
    }

    private void load() {
        try (InputStream in = new ClassPathResource(DATA_FILE).getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            JsonNode list = root.path("cities");
            if (!list.isArray()) {
                throw new IllegalStateException("城市交通档案缺少 cities 数组：" + DATA_FILE);
            }
            for (JsonNode node : list) {
                CityInfo info = new CityInfo(
                        node.path("name").asText(""),
                        node.path("adcode").asText(""),
                        node.path("hasMetro").asBoolean(false),
                        node.path("hasRailway").asBoolean(true),
                        readAliases(node.path("aliases"))
                );
                if (info.name().isBlank()) {
                    continue;
                }
                cities.put(normalize(info.name()), info);
                for (String alias : info.aliases()) {
                    cities.put(normalize(alias), info);
                }
                if (!info.adcode().isBlank()) {
                    cities.put(info.adcode(), info);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取城市交通档案失败：" + DATA_FILE, e);
        }
    }

    private static List<String> readAliases(JsonNode aliases) {
        List<String> result = new ArrayList<>();
        if (aliases.isArray()) {
            for (JsonNode alias : aliases) {
                String value = alias.asText("").trim();
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 查找城市档案；未收录返回空。 */
    public Optional<CityInfo> find(String city) {
        String key = normalize(city);
        return key.isBlank() ? Optional.empty() : Optional.ofNullable(cities.get(key));
    }

    /** 城市是否有地铁。 */
    public boolean hasMetro(String city) {
        return find(city).map(CityInfo::hasMetro).orElse(false);
    }

    /** 给路径规划使用的城市编码：优先 adcode，未知城市退回城市名。 */
    public String cityCode(String city) {
        Optional<CityInfo> info = find(city);
        if (info.isPresent()) {
            return info.get().adcode();
        }
        return city == null ? "" : city.trim();
    }

    /** 两个地点是否属于同一城市。 */
    public boolean isSameCity(String cityA, String cityB) {
        String a = normalize(cityA);
        String b = normalize(cityB);
        return !a.isBlank() && a.equals(b);
    }

    private static String normalize(String city) {
        if (city == null) {
            return "";
        }
        String value = city.trim().toLowerCase(Locale.ROOT);
        if (value.endsWith("市")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 城市交通档案。 */
    public record CityInfo(
            String name,
            String adcode,
            boolean hasMetro,
            boolean hasRailway,
            List<String> aliases) {
    }
}
