package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 高德地图 Web 服务 API 封装：
 * <ul>
 *   <li>POI 地点搜索（餐厅、酒店、景点等）</li>
 *   <li>道路实时交通态势（拥堵情况）</li>
 * </ul>
 * 需在 application.properties 中配置：
 * <pre>
 * amap.api-key=${AMAP_API_KEY:}
 * </pre>
 */
@Service
public class AmapService {

    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    private static final String POI_TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final String TRAFFIC_ROAD_URL = "https://restapi.amap.com/v3/traffic/status/road";
    private static final String POI_DETAIL_URL = "https://restapi.amap.com/v3/place/detail";
    private static final String DRIVING_ROUTE_URL = "https://restapi.amap.com/v3/direction/driving";

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @Value("${amap.api-key:}")
    private String apiKey;

    /**
     * 按关键词/分类搜索地点。
     *
     * @param keywords 搜索关键词（如“餐厅”“西湖”），可为空但 types 不能同时为空
     * @param types    地点分类（分类代码或汉字，如“风景名胜”），可为空
     * @param city     城市名或 adcode，可为空（缺省时全国范围搜索）
     * @param limit    最多返回条数
     */
    public List<PoiInfo> searchPoi(String keywords, String types, String city, int limit) throws IOException {
        requireKey();
        HttpUrl.Builder url = HttpUrl.get(POI_TEXT_URL).newBuilder()
                .addQueryParameter("key", apiKey);
        if (!isBlank(keywords)) {
            url.addQueryParameter("keywords", keywords);
        }
        if (!isBlank(types)) {
            url.addQueryParameter("types", types);
        }
        if (!isBlank(city)) {
            url.addQueryParameter("city", city);
        }
        url.addQueryParameter("offset", String.valueOf(Math.min(Math.max(limit, 1), 10)));
        url.addQueryParameter("page", "1");
        url.addQueryParameter("extensions", "all");

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);

        List<PoiInfo> result = new ArrayList<>();
        JsonNode pois = resp.path("pois");
        if (!pois.isArray()) {
            return result;
        }
        for (JsonNode poi : pois) {
            if (result.size() >= limit) {
                break;
            }
            result.add(new PoiInfo(
                    poi.path("id").asText(""),
                    poi.path("name").asText(""),
                    poi.path("type").asText(""),
                    poi.path("address").asText(""),
                    poi.path("cityname").asText(""),
                    poi.path("tel").asText(""),
                    poi.path("biz_ext").path("rating").asText(""),
                    poi.path("location").asText("")
            ));
        }
        return result;
    }

    /** 查询指定城市某条道路的实时交通态势。 */
    public TrafficInfo getRoadTraffic(String city, String road, String level) throws IOException {
        requireKey();
        HttpUrl.Builder url = HttpUrl.get(TRAFFIC_ROAD_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("city", city)
                .addQueryParameter("name", road);
        if (!isBlank(level)) {
            url.addQueryParameter("level", level);
        }

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);

        JsonNode traffic = resp.path("trafficinfo");
        String status = traffic.path("status").asText("");
        List<RoadTraffic> roads = new ArrayList<>();
        JsonNode roadsNode = traffic.path("roads");
        if (roadsNode.isArray()) {
            for (JsonNode r : roadsNode) {
                roads.add(new RoadTraffic(
                        r.path("name").asText(""),
                        statusText(r.path("status").asText("")),
                        r.path("direction").asText(""),
                        r.path("speed").asText(""),
                        r.path("description").asText("")
                ));
            }
        }
        return new TrafficInfo(statusText(status), traffic.path("description").asText(""), roads);
    }

    /** 查询单个景点详情（评分、等级、开放时间等）。 */
    public AttractionDetail getAttractionDetail(String city, String name) throws IOException {
        requireKey();
        List<PoiInfo> pois = searchPoi(name, "风景名胜", city, 1);
        if (pois.isEmpty() || pois.get(0).id().isBlank()) {
            throw new IOException("未找到景点：" + name + "，请确认城市和景点名称");
        }

        HttpUrl.Builder url = HttpUrl.get(POI_DETAIL_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("id", pois.get(0).id())
                .addQueryParameter("extensions", "all");

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);
        JsonNode detail = resp.path("pois").path(0);
        if (detail.isMissingNode() || detail.isNull()) {
            throw new IOException("景点详情接口未返回数据");
        }

        JsonNode biz = detail.path("biz_ext");
        String openTime = biz.path("opentime2").asText("");
        if (openTime.isBlank()) {
            openTime = biz.path("open_time").asText("");
        }
        return new AttractionDetail(
                detail.path("name").asText(""),
                detail.path("type").asText(""),
                detail.path("address").asText(""),
                detail.path("tel").asText(""),
                biz.path("rating").asText(""),
                biz.path("level").asText(""),
                openTime,
                biz.path("cost").asText("")
        );
    }

    /** 查询两个地点之间的驾车路线（距离、预计耗时、过路费）。 */
    public RouteInfo getDrivingRoute(String origin, String destination, String city) throws IOException {
        requireKey();
        PoiInfo from = resolvePoi(origin, city);
        PoiInfo to = resolvePoi(destination, city);

        HttpUrl.Builder url = HttpUrl.get(DRIVING_ROUTE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", from.location())
                .addQueryParameter("destination", to.location())
                .addQueryParameter("extensions", "base");

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);
        JsonNode path = resp.path("route").path("paths").path(0);
        if (path.isMissingNode() || path.isNull()) {
            throw new IOException("路径规划接口未返回路线");
        }
        return new RouteInfo(
                from.name(),
                to.name(),
                path.path("distance").asText(""),
                path.path("duration").asText(""),
                path.path("tolls").asText("")
        );
    }

    private PoiInfo resolvePoi(String keyword, String city) throws IOException {
        List<PoiInfo> pois = searchPoi(keyword, null, city, 1);
        if (pois.isEmpty() || pois.get(0).location().isBlank()) {
            throw new IOException("未找到地点：" + keyword + "，请提供更完整的地点名称或城市");
        }
        return pois.get(0);
    }

    private void requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未配置高德地图 API Key：请检查环境变量 AMAP_API_KEY，或在 application.properties 中设置 amap.api-key");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String statusText(String code) {
        return switch (code) {
            case "1" -> "畅通";
            case "2" -> "缓行";
            case "3" -> "拥堵";
            case "4" -> "严重拥堵";
            default -> "未知";
        };
    }

    private void checkStatus(JsonNode resp) throws IOException {
        if (!"1".equals(resp.path("status").asText())) {
            throw new IOException("高德接口返回错误：" + resp.path("info").asText(""));
        }
    }

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + text);
            }
            return mapper.readTree(text);
        }
    }

    /** POI 地点信息 */
    public record PoiInfo(String id, String name, String type, String address, String city, String tel, String rating, String location) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            if (!rating.isBlank()) {
                sb.append("（评分").append(rating).append("）");
            }
            if (!type.isBlank()) {
                sb.append("，类型：").append(type);
            }
            if (!address.isBlank()) {
                sb.append("，地址：").append(address);
            }
            if (!tel.isBlank()) {
                sb.append("，电话：").append(tel);
            }
            return sb.toString();
        }
    }

    /** 道路交通态势 */
    public record TrafficInfo(String statusText, String description, List<RoadTraffic> roads) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("交通态势：").append(statusText);
            if (!description.isBlank()) {
                sb.append("。").append(description);
            }
            if (!roads.isEmpty()) {
                sb.append("；路段：");
                for (int i = 0; i < roads.size(); i++) {
                    if (i > 0) {
                        sb.append("；");
                    }
                    sb.append(i + 1).append(". ").append(roads.get(i));
                }
            }
            return sb.toString();
        }
    }

    /** 单条道路路况 */
    public record RoadTraffic(String name, String statusText, String direction, String speed, String description) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (!name.isBlank()) {
                sb.append(name);
            }
            if (!direction.isBlank()) {
                sb.append("（").append(direction).append("）");
            }
            if (!statusText.isBlank()) {
                sb.append("：").append(statusText);
            }
            if (!speed.isBlank()) {
                sb.append("，速度 ").append(speed).append(" km/h");
            }
            return sb.toString();
        }
    }

    /** 单个景点详情 */
    public record AttractionDetail(String name, String type, String address, String tel, String rating, String level, String openTime, String cost) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            if (!rating.isBlank()) {
                sb.append("，评分：").append(rating);
            }
            if (!level.isBlank()) {
                sb.append("，等级：").append(level);
            }
            if (!openTime.isBlank()) {
                sb.append("，开放时间：").append(openTime);
            }
            if (!address.isBlank()) {
                sb.append("，地址：").append(address);
            }
            if (!tel.isBlank()) {
                sb.append("，电话：").append(tel);
            }
            return sb.toString();
        }
    }

    /** 驾车路线信息 */
    public record RouteInfo(String originName, String destinationName, String distance, String duration, String tolls) {
        @Override
        public String toString() {
            double km = safeDouble(distance) / 1000.0;
            int minutes = (int) Math.ceil(safeDouble(duration) / 60.0);
            String tollText = safeDouble(tolls) == 0 ? "无过路费" : "过路费 " + tolls + " 元";
            return String.format("%s → %s：约 %.1f 公里，驾车约 %d 分钟，%s",
                    originName, destinationName, km, minutes, tollText);
        }

        private static double safeDouble(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
