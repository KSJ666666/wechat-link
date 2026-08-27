package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.autotrip.common.model.RouteOption;
import com.group.autotrip.common.model.TransportMode;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class AmapService {

    private static final String POI_TEXT_URL = "https://restapi.amap.com/v3/place/text";
    private static final String TRAFFIC_ROAD_URL = "https://restapi.amap.com/v3/traffic/status/road";
    private static final String POI_DETAIL_URL = "https://restapi.amap.com/v3/place/detail";
    private static final String DRIVING_ROUTE_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String WALKING_ROUTE_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String TRANSIT_ROUTE_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";
    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final int MAX_MATRIX_DESTINATIONS = 50;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private final Semaphore amapPermits = new Semaphore(2);

    @Value("${amap.api-key:}")
    private String apiKey;

    public List<PoiInfo> searchPoi(String keywords, String types, String city, int limit) throws IOException {
        requireKey();
        HttpUrl.Builder url = HttpUrl.get(POI_TEXT_URL).newBuilder().addQueryParameter("key", apiKey);
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

    public TrafficInfo getRoadTraffic(String cityOrAdcode, String road, String level) throws IOException {
        requireKey();
        HttpUrl.Builder url = HttpUrl.get(TRAFFIC_ROAD_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("name", road);
        if (!isBlank(cityOrAdcode)) {
            if (cityOrAdcode.trim().matches("\\d{6}")) {
                url.addQueryParameter("adcode", cityOrAdcode.trim());
            } else {
                url.addQueryParameter("city", cityOrAdcode.trim());
            }
        }
        if (!isBlank(level)) {
            url.addQueryParameter("level", level);
        }
        url.addQueryParameter("extensions", "all");

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
        if (roads.isEmpty()) {
            throw new IOException("未查询到该道路的实时路况");
        }
        return new TrafficInfo(statusText(status), traffic.path("description").asText(""), roads);
    }

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

    public RouteInfo getDrivingRoute(String origin, String destination, String city) throws IOException {
        PoiInfo from = resolvePoi(origin, city);
        PoiInfo to = resolvePoi(destination, city);
        RouteOption option = getDrivingRouteOption(from, to);
        return new RouteInfo(
                option.originName(),
                option.destinationName(),
                String.valueOf(option.distanceMeters()),
                String.valueOf(option.durationSeconds()),
                option.cost()
        );
    }

    public RouteOption getDrivingRouteOption(PoiInfo from, PoiInfo to) throws IOException {
        requireKey();
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
        return new RouteOption(TransportMode.DRIVING, from.name(), to.name(), safeLong(path.path("distance").asText("")), safeLong(path.path("duration").asText("")), path.path("tolls").asText(""), "驾车");
    }

    public RouteOption getWalkingRoute(PoiInfo from, PoiInfo to) throws IOException {
        requireKey();
        HttpUrl.Builder url = HttpUrl.get(WALKING_ROUTE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", from.location())
                .addQueryParameter("destination", to.location());

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);
        JsonNode path = resp.path("route").path("paths").path(0);
        if (path.isMissingNode() || path.isNull()) {
            throw new IOException("步行路径规划接口未返回路线");
        }
        return new RouteOption(TransportMode.WALKING, from.name(), to.name(), safeLong(path.path("distance").asText("")), safeLong(path.path("duration").asText("")), "", "步行");
    }

    public List<RouteOption> getTransitRoutes(PoiInfo from, PoiInfo to, String city, String cityd) throws IOException {
        requireKey();
        HttpUrl.Builder url = HttpUrl.get(TRANSIT_ROUTE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origin", from.location())
                .addQueryParameter("destination", to.location())
                .addQueryParameter("strategy", "0")
                .addQueryParameter("extensions", "base");
        if (!isBlank(city)) {
            url.addQueryParameter("city", city);
        }
        if (!isBlank(cityd)) {
            url.addQueryParameter("cityd", cityd);
        }

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);
        JsonNode transits = resp.path("route").path("transits");
        if (!transits.isArray()) {
            return List.of();
        }

        EnumMap<TransportMode, RouteOption> best = new EnumMap<>(TransportMode.class);
        for (JsonNode transit : transits) {
            TransportMode mode = transitMode(transit);
            if (mode == null) {
                continue;
            }
            long duration = safeLong(transit.path("duration").asText(""));
            RouteOption option = new RouteOption(
                    mode,
                    from.name(),
                    to.name(),
                    safeLong(transit.path("distance").asText("")),
                    duration,
                    transit.path("cost").asText(""),
                    transitSummary(transit, mode)
            );
            RouteOption prev = best.get(mode);
            if (prev == null || duration < prev.durationSeconds()) {
                best.put(mode, option);
            }
        }
        return List.copyOf(best.values());
    }

    public List<DistanceInfo> getDistanceMatrix(String origin, List<String> destinations, String city, boolean driving) throws IOException {
        requireKey();
        List<String> uniqueNames = destinations.stream().map(String::trim).filter(s -> !s.isBlank()).distinct().limit(MAX_MATRIX_DESTINATIONS).toList();
        if (uniqueNames.isEmpty()) {
            throw new IllegalArgumentException("destinations 参数不能为空");
        }

        PoiInfo originPoi = resolvePoi(origin, city);
        List<PoiInfo> resolved = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String name : uniqueNames) {
            try {
                resolved.add(resolvePoi(name, city));
            } catch (PoiNotFoundException e) {
                failures.add(name);
            }
        }
        if (resolved.isEmpty()) {
            throw new IOException("未找到任何目的地：" + String.join("、", uniqueNames));
        }

        String origins = resolved.stream().map(PoiInfo::location).collect(Collectors.joining("|"));
        HttpUrl.Builder url = HttpUrl.get(DISTANCE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("origins", origins)
                .addQueryParameter("destination", originPoi.location())
                .addQueryParameter("type", driving ? "1" : "0");

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);

        List<DistanceInfo> distances = new ArrayList<>();
        JsonNode results = resp.path("results");
        int count = Math.min(results.size(), resolved.size());
        for (int i = 0; i < count; i++) {
            PoiInfo poi = resolved.get(i);
            JsonNode r = results.get(i);
            double km = r.path("distance").asDouble(0) / 1000.0;
            int minutes = driving ? (int) Math.ceil(r.path("duration").asDouble(0) / 60.0) : 0;
            distances.add(new DistanceInfo(poi.name(), km, minutes, ""));
        }
        distances.sort(Comparator.comparingDouble(DistanceInfo::distanceKm).thenComparing(DistanceInfo::name));
        for (String failure : failures) {
            distances.add(new DistanceInfo(failure, 0, 0, "未找到：" + failure));
        }
        return distances;
    }

    public PoiInfo resolvePoi(String keyword, String city) throws IOException {
        List<PoiInfo> pois = searchPoi(keyword, null, city, 1);
        if (!pois.isEmpty() && !pois.get(0).location().isBlank()) {
            return pois.get(0);
        }
        return geocodePlace(keyword, city);
    }

    private PoiInfo geocodePlace(String name, String city) throws IOException {
        HttpUrl.Builder url = HttpUrl.get(GEOCODE_URL).newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("address", name);
        if (!isBlank(city)) {
            url.addQueryParameter("city", city);
        }

        JsonNode resp = getJson(url.build().toString());
        checkStatus(resp);
        JsonNode geocodes = resp.path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            throw new PoiNotFoundException("未找到地点：" + name + "，请提供更完整的地点名称或城市");
        }
        JsonNode geo = geocodes.get(0);
        String location = geo.path("location").asText("");
        if (location.isBlank()) {
            throw new PoiNotFoundException("未找到地点：" + name + "，请提供更完整的地点名称或城市");
        }
        return new PoiInfo("", name, "", geo.path("formatted_address").asText(""), geo.path("city").asText(""), "", "", location);
    }

    private static TransportMode transitMode(JsonNode transit) {
        boolean rail = false;
        boolean metro = false;
        boolean bus = false;
        JsonNode segments = transit.path("segments");
        if (!segments.isArray()) {
            return null;
        }
        for (JsonNode segment : segments) {
            JsonNode railway = segment.path("railway");
            if (railway.isObject() && !railway.path("trip").asText("").isBlank()) {
                rail = true;
            }
            JsonNode busNode = segment.path("bus");
            JsonNode busLines = busNode.isArray() ? busNode : busNode.path("buslines");
            if (busLines.isArray()) {
                for (JsonNode busLine : busLines) {
                    if (busLine.path("type").asText("").contains("地铁")) {
                        metro = true;
                    } else {
                        bus = true;
                    }
                }
            }
        }
        if (rail) return TransportMode.RAIL;
        if (metro) return TransportMode.METRO;
        if (bus) return TransportMode.BUS;
        return null;
    }

    private static String transitSummary(JsonNode transit, TransportMode mode) {
        List<String> names = new ArrayList<>();
        JsonNode segments = transit.path("segments");
        if (segments.isArray()) {
            for (JsonNode segment : segments) {
                if (mode == TransportMode.RAIL) {
                    JsonNode railway = segment.path("railway");
                    if (railway.isObject() && !railway.path("trip").asText("").isBlank()) {
                        String departure = railway.path("departure_stop").path("name").asText("");
                        String arrival = railway.path("arrival_stop").path("name").asText("");
                        String trip = railway.path("trip").asText("");
                        String label = railLabel(railway.path("type").asText(""));
                        if (departure.isBlank() || arrival.isBlank()) {
                            names.add(label + " " + trip);
                        } else {
                            names.add(label + " " + trip + "（" + departure + " → " + arrival + "）");
                        }
                    }
                } else {
                    JsonNode busNode = segment.path("bus");
                    JsonNode busLines = busNode.isArray() ? busNode : busNode.path("buslines");
                    if (busLines.isArray()) {
                        for (JsonNode busLine : busLines) {
                            String type = busLine.path("type").asText("");
                            boolean isMetro = type.contains("地铁");
                            if ((mode == TransportMode.METRO && !isMetro) || (mode == TransportMode.BUS && isMetro)) {
                                continue;
                            }
                            String name = lineName(busLine.path("name").asText(""));
                            if (!name.isBlank()) {
                                names.add(name);
                            }
                        }
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return mode.displayName();
        }
        String joined = String.join(" / ", names);
        return mode == TransportMode.BUS ? "公交 " + joined : joined;
    }

    private static String railLabel(String type) {
        return switch (type) {
            case "2011" -> "高铁";
            case "2012" -> "动车";
            case "2013" -> "城际";
            default -> "火车";
        };
    }

    private static String lineName(String name) {
        int idx = name.indexOf('(');
        if (idx > 0) {
            return name.substring(0, idx).trim();
        }
        return name;
    }

    private static long safeLong(String value) {
        try {
            return (long) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void requireKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置高德地图 API Key：请检查环境变量 AMAP_API_KEY，或在 application.properties 中设置 amap.api-key");
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
            String code = resp.path("infocode").asText("");
            String info = resp.path("info").asText("");
            throw new IOException(amapErrorMessage(code, info));
        }
    }

    private static String amapErrorMessage(String code, String info) {
        String key = info.isBlank() ? code : info;
        if ("CUQPS_HAS_EXCEEDED_THE_LIMIT".equals(key)) {
            return "高德请求过于频繁，请稍后再试";
        }
        if ("INVALID_USER_KEY".equals(key)) {
            return "高德 API Key 无效，请检查 amap.api-key";
        }
        if ("DAILY_QUERY_OVER_LIMIT".equals(key)) {
            return "高德今日请求配额已用完";
        }
        return "高德接口返回错误：" + (info.isBlank() ? code : info);
    }

    private static boolean isRateLimited(String message) {
        return message != null && message.contains("过于频繁");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode getJson(String url) throws IOException {
        try {
            amapPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("高德请求被中断", e);
        }
        try {
            return fetchChecked(url);
        } catch (IOException e) {
            if (isRateLimited(e.getMessage())) {
                sleepQuietly(500);
                return fetchChecked(url);
            }
            throw e;
        } finally {
            amapPermits.release();
        }
    }

    private JsonNode fetchChecked(String url) throws IOException {
        JsonNode resp = executeJson(url);
        checkStatus(resp);
        return resp;
    }

    private JsonNode executeJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = http.newCall(request).execute()) {
            String text = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ": " + text);
            }
            return mapper.readTree(text);
        }
    }

    public static final class PoiNotFoundException extends IOException {
        public PoiNotFoundException(String message) { super(message); }
    }

    public record PoiInfo(String id, String name, String type, String address, String city, String tel, String rating, String location) {
        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            if (!rating.isBlank()) sb.append("（评分").append(rating).append("）");
            if (!type.isBlank()) sb.append("，类型：").append(type);
            if (!address.isBlank()) sb.append("，地址：").append(address);
            if (!tel.isBlank()) sb.append("，电话：").append(tel);
            return sb.toString();
        }
    }

    public record TrafficInfo(String statusText, String description, List<RoadTraffic> roads) {
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("交通态势：").append(statusText);
            if (!description.isBlank()) sb.append("。").append(description);
            if (!roads.isEmpty()) {
                sb.append("；路段：");
                for (int i = 0; i < roads.size(); i++) {
                    if (i > 0) sb.append("；");
                    sb.append(i + 1).append(". ").append(roads.get(i));
                }
            }
            return sb.toString();
        }
    }

    public record RoadTraffic(String name, String statusText, String direction, String speed, String description) {
        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            if (!name.isBlank()) sb.append(name);
            if (!direction.isBlank()) sb.append("（").append(direction).append("）");
            if (!statusText.isBlank()) sb.append("：").append(statusText);
            if (!speed.isBlank()) sb.append("，速度 ").append(speed).append(" km/h");
            return sb.toString();
        }
    }

    public record AttractionDetail(String name, String type, String address, String tel, String rating, String level, String openTime, String cost) {
        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(name);
            if (!rating.isBlank()) sb.append("，评分：").append(rating);
            if (!level.isBlank()) sb.append("，等级：").append(level);
            if (!openTime.isBlank()) sb.append("，开放时间：").append(openTime);
            if (!address.isBlank()) sb.append("，地址：").append(address);
            if (!tel.isBlank()) sb.append("，电话：").append(tel);
            return sb.toString();
        }
    }

    public record RouteInfo(String originName, String destinationName, String distance, String duration, String tolls) {
        @Override public String toString() {
            double km = safeDouble(distance) / 1000.0;
            int minutes = (int) Math.ceil(safeDouble(duration) / 60.0);
            String tollText = safeDouble(tolls) == 0 ? "无过路费" : "过路费 " + tolls + " 元";
            return String.format("%s → %s：约 %.1f 公里，驾车约 %d 分钟，%s", originName, destinationName, km, minutes, tollText);
        }
        private static double safeDouble(String value) {
            try { return Double.parseDouble(value); } catch (NumberFormatException e) { return 0; }
        }
    }

    public record DistanceInfo(String name, double distanceKm, int durationMin, String note) {
        @Override public String toString() {
            if (!note.isBlank()) return note;
            if (durationMin > 0) return String.format("%s：约 %.1f 公里，约 %d 分钟", name, distanceKm, durationMin);
            return String.format("%s：约 %.1f 公里", name, distanceKm);
        }
    }
}
