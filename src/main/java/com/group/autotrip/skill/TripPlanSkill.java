package com.group.autotrip.skill;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.group.autotrip.agent.TripPlanStore;
import com.group.autotrip.common.model.DayPlan;
import com.group.autotrip.common.model.Itinerary;
import com.group.autotrip.common.model.PlanStatus;
import com.group.autotrip.common.model.Route;
import com.group.autotrip.common.model.RouteKind;
import com.group.autotrip.output.ItineraryOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自驾/旅行规划 Skill：解析需求（LLM 优先、正则兜底）→ 工具取数 →
 * LLM 生成结构化行程单（{@link Itinerary}）→ 存储 → 排版回复；
 * 支持基于已存行程单的重规划（"第二天太赶了，重新排"）。
 */
@Component
public class TripPlanSkill implements Skill {

    private static final Logger log = LoggerFactory.getLogger(TripPlanSkill.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\s*\\d+[.、]\\s*(.+)$");
    private static final Pattern DAYS_PATTERN = Pattern.compile("(\\d+|[一二两三四五六七八九十])\\s*[天晚]");

    private static final List<String> PLAN_WORDS = List.of(
            "自驾", "行程", "规划", "路书", "行程单", "怎么玩", "怎么去",
            "出发地", "目的地", "顺路", "沿途", "出游", "几天", "几晚");
    private static final List<String> REPLAN_WORDS = List.of(
            "重排", "重新规划", "重新排", "调整", "太赶", "太满", "太累", "改一下");

    private final TripPlanStore store;

    public TripPlanSkill(TripPlanStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "trip_plan_skill";
    }

    @Override
    public String description() {
        return "处理明确的自驾旅行规划需求（如“从杭州到黄山三天自驾”），串联路线、景点、距离和天气工具，"
                + "生成结构化行程单；也处理对已有行程单的重规划（如“第二天太赶了，重新排”）。";
    }

    @Override
    public boolean supports(String userText) {
        if (userText == null) {
            return false;
        }
        return containsAny(userText, PLAN_WORDS)
                || DAYS_PATTERN.matcher(userText).find()
                || containsAny(userText, REPLAN_WORDS);
    }

    @Override
    public String execute(String userText, SkillContext ctx) throws Exception {
        // 重规划优先：已有行程单时按反馈重新生成
        if (containsAny(userText, REPLAN_WORDS)) {
            if (!store.has(ctx.userId())) {
                return "还没有已保存的行程单，先规划一个再调整吧。例如：从杭州到黄山三天自驾。";
            }
            return replan(userText, ctx);
        }

        ParsedTripRequest request = parseRequest(userText, ctx);
        if (request.destination().isBlank() && request.origin().isBlank()) {
            return "我已经命中旅行规划技能，但还缺目的地。你可以直接告诉我“从哪到哪、几天、偏景点还是偏休闲”。";
        }

        ToolData data = collectToolData(userText, request, ctx);
        Itinerary itinerary = buildItinerary(userText, request, data, ctx);
        store.save(ctx.userId(), itinerary);

        StringBuilder reply = new StringBuilder(ItineraryOutput.render(itinerary));
        appendWeatherSections(reply, data);
        reply.append("\n\n如需调整，可以说“重新排”或“第二天太赶了，改一下”。");
        return reply.toString();
    }

    // ===== 重规划 =====

    private String replan(String userText, SkillContext ctx) throws Exception {
        Itinerary previous = store.get(ctx.userId());
        String prompt = """
                你是一个自驾旅行规划助手。用户对上一版行程单提出了调整意见，请生成新版行程单 JSON。
                要求：
                1. 只输出 JSON，字段结构与上一版相同：{title, route{start,end,waypoints,distanceKm,durationMin,routeType}, days[{day,date,spots[{name,address,openTime,ticketPrice,brief,tip}],stayCity,drivingKm,note}], budget, budgetItems[{category,amount,note}], notes, status:"草拟"}。
                2. 针对用户意见修改对应部分，其余内容尽量保留；日期沿用上一版或从今天起连续排。
                3. 不要编造新的景点名。

                用户意见：%s

                上一版行程单 JSON：
                %s
                """.formatted(userText, MAPPER.writeValueAsString(previous));
        try {
            String json = firstJsonObject(ctx.llm().chat(prompt));
            if (json != null) {
                Itinerary updated = MAPPER.readValue(json, Itinerary.class);
                store.save(ctx.userId(), updated);
                return ItineraryOutput.render(updated) + "\n\n已按你的反馈重新排好，还有不满意的地方继续说。";
            }
        } catch (Exception e) {
            log.warn("重规划失败：{}", e.getMessage());
        }
        return "重排失败了，可以再试一次，或重新描述你的需求（例如“帮我规划三天杭州行程”）。";
    }

    // ===== 结构化行程单 =====

    private Itinerary buildItinerary(String userText, ParsedTripRequest request, ToolData data, SkillContext ctx) {
        String schemaHint = """
                {
                  "title": "行程标题",
                  "route": {"start": "出发地", "end": "目的地", "waypoints": [], "distanceKm": 0, "durationMin": 0, "routeType": "高速"},
                  "days": [{"day": 1, "date": "2026-08-28", "spots": [{"name": "景点", "address": "地址", "openTime": "开放时间", "ticketPrice": 0, "brief": "简介", "tip": "提示"}], "stayCity": "住宿城市", "drivingKm": 0, "note": "当日说明"}],
                  "budget": 0,
                  "budgetItems": [{"category": "住宿", "amount": 0, "note": "说明"}],
                  "notes": ["注意事项"],
                  "status": "草拟"
                }
                """;
        String prompt = """
                你是一个自驾旅行规划助手。请根据下面的结构化需求与工具结果，生成一版旅行行程单 JSON。
                要求：
                1. 只输出 JSON，不要输出任何其他内容。
                2. 字段结构严格按示例：%s
                3. days 的天数等于需求天数，date 从 %s 起连续排；每天 2-3 个景点，只使用工具结果中出现的景点名，不要编造。
                4. budget 为总预算（元），budgetItems 按住宿/门票/餐饮/油费/过路费等分类估算。
                5. route.routeType 只取：高速、国道、省道、其他。
                6. 没有数据支撑的字段填空或 0。

                用户需求与工具结果：
                %s
                """.formatted(schemaHint, LocalDate.now(), buildContext(userText, request, data));
        try {
            String json = firstJsonObject(ctx.llm().chat(prompt));
            if (json != null) {
                return MAPPER.readValue(json, Itinerary.class);
            }
        } catch (Exception e) {
            log.warn("LLM 行程单解析失败，使用兜底行程单：{}", e.getMessage());
        }
        return fallbackItinerary(request, data);
    }

    /** LLM 不可用或 JSON 解析失败时的兜底行程单 */
    static Itinerary fallbackItinerary(ParsedTripRequest request, ToolData data) {
        List<DayPlan> days = new ArrayList<>();
        LocalDate start = LocalDate.now();
        for (int i = 0; i < Math.max(1, request.days()); i++) {
            days.add(new DayPlan(i + 1, start.plusDays(i), List.of(), request.destination(), 0, ""));
        }
        Route route = new Route(request.origin(), request.destination(), List.of(), 0, 0, RouteKind.OTHER);
        String title = (request.origin().isBlank() ? "" : request.origin() + "→")
                + request.destination() + " " + Math.max(1, request.days()) + "天行程";
        List<String> notes = new ArrayList<>();
        notes.add("本行程单为兜底版本，可回复“重新排”或补充更多信息后再次生成。");
        if (!data.attractions().isBlank()) {
            notes.add("参考景点见下方检索结果，可据此自行安排。");
        }
        return new Itinerary(title, route, days, 0, notes, PlanStatus.DRAFT, List.of());
    }

    private static String buildContext(String userText, ParsedTripRequest request, ToolData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户原话：").append(userText).append("\n");
        sb.append("出发地：").append(nonBlank(request.origin(), "未提供")).append("\n");
        sb.append("目的地：").append(nonBlank(request.destination(), "未提供")).append("\n");
        sb.append("天数：").append(Math.max(1, request.days())).append("\n");
        sb.append("偏好：").append(nonBlank(request.style(), "未提供")).append("\n");
        appendSection(sb, "实时天气", data.weatherNow());
        appendSection(sb, "天气预报", data.forecast());
        appendSection(sb, "路线推荐", data.route());
        appendSection(sb, "路况参考", data.traffic());
        appendSection(sb, "景点列表", data.attractions());
        appendSection(sb, "重点景点详情", String.join("\n", data.attractionDetails()));
        appendSection(sb, "景点顺路排序", data.matrix());
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, String content) {
        if (content != null && !content.isBlank()) {
            sb.append(title).append("：\n").append(content.trim()).append("\n");
        }
    }

    private static void appendWeatherSections(StringBuilder reply, ToolData data) {
        if (!data.weatherNow().isBlank() || !data.forecast().isBlank()) {
            reply.append("\n沿途天气参考：\n");
            if (!data.weatherNow().isBlank()) {
                reply.append(data.weatherNow().trim()).append("\n");
            }
            if (!data.forecast().isBlank()) {
                reply.append(data.forecast().trim()).append("\n");
            }
        }
    }

    // ===== 工具取数 =====

    /** 工具取数结果（包内可见，便于测试兜底行程单） */
    record ToolData(String weatherNow, String forecast, String route, String traffic,
                    String attractions, List<String> attractionDetails, String matrix) {
    }

    private ToolData collectToolData(String userText, ParsedTripRequest request, SkillContext ctx) {
        String weatherNow = execTool(ctx, "query_weather",
                MAPPER.createObjectNode().put("location", request.destination()));
        String forecast = execTool(ctx, "query_weather_forecast",
                MAPPER.createObjectNode()
                        .put("location", request.destination())
                        .put("days", Math.min(Math.max(request.days(), 1), 5)));

        String route = "";
        if (!request.origin().isBlank()) {
            ObjectNode routeArgs = MAPPER.createObjectNode()
                    .put("origin", request.origin())
                    .put("destination", request.destination())
                    .put("mode", "all")
                    .put("prefer", request.style().isBlank() ? "avoid_driving" : "");
            if (!request.city().isBlank()) {
                routeArgs.put("city", request.city());
            }
            route = execTool(ctx, "query_route", routeArgs);
        }

        String traffic = "";
        if (shouldUseTraffic(userText)) {
            String road = guessRoad(userText);
            if (!road.isBlank()) {
                traffic = execTool(ctx, "query_traffic",
                        MAPPER.createObjectNode()
                                .put("city", nonBlank(request.destination(), request.origin()))
                                .put("road", road));
            }
        }

        String attractions = execTool(ctx, "query_attractions",
                MAPPER.createObjectNode()
                        .put("city", request.destination())
                        .put("limit", 5));
        List<String> attractionNames = extractItemNames(attractions, 3);
        List<String> attractionDetails = new ArrayList<>();
        for (String name : attractionNames) {
            String detail = execTool(ctx, "query_attraction_detail",
                    MAPPER.createObjectNode()
                            .put("city", request.destination())
                            .put("name", name));
            if (!detail.isBlank()) {
                attractionDetails.add(detail);
            }
        }

        String matrix = "";
        if (!request.origin().isBlank() && !attractionNames.isEmpty()) {
            ObjectNode matrixArgs = MAPPER.createObjectNode()
                    .put("origin", request.origin())
                    .put("city", request.destination())
                    .put("mode", "driving");
            var arr = matrixArgs.putArray("destinations");
            for (String name : attractionNames) {
                arr.add(name);
            }
            matrix = execTool(ctx, "query_distance_matrix", matrixArgs);
        }
        return new ToolData(weatherNow, forecast, route, traffic, attractions, attractionDetails, matrix);
    }

    private String execTool(SkillContext ctx, String name, JsonNode args) {
        try {
            return ctx.tools().execute(name, args);
        } catch (Exception e) {
            return "";
        }
    }

    // ===== 解析 =====

    /** LLM 优先解析需求，失败时正则兜底 */
    private ParsedTripRequest parseRequest(String userText, SkillContext ctx) {
        try {
            String prompt = "从用户消息中提取自驾行程需求，只输出 JSON："
                    + "{\"origin\":\"出发地\",\"destination\":\"目的地\",\"days\":3,\"style\":\"偏好\"}，"
                    + "无法确定的字段填空字符串。\n用户消息：" + userText;
            String json = firstJsonObject(ctx.llm().chat(prompt));
            if (json != null) {
                JsonNode node = MAPPER.readTree(json);
                ParsedTripRequest parsed = new ParsedTripRequest(
                        node.path("origin").asText(""),
                        node.path("destination").asText(""),
                        Math.max(1, node.path("days").asInt(3)),
                        node.path("style").asText(""),
                        "");
                if (!parsed.destination().isBlank() || !parsed.origin().isBlank()) {
                    return parsed;
                }
            }
        } catch (Exception e) {
            log.debug("LLM 解析行程需求失败，使用正则兜底：{}", e.getMessage());
        }
        return ParsedTripRequest.from(userText);
    }

    /** 从 LLM 回复中提取首个 JSON 对象（容忍前后多余文字或代码块标记） */
    static String firstJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static boolean shouldUseTraffic(String userText) {
        if (userText == null) {
            return false;
        }
        return userText.contains("路况") || userText.contains("堵车")
                || userText.contains("拥堵") || userText.contains("高速");
    }

    /** 兜底猜测消息中的道路名：找到最早出现的路名后缀后向前扫描，遇到标点或动词截断 */
    static String guessRoad(String userText) {
        if (userText == null) {
            return "";
        }
        String[] suffixes = {"快速路", "高速路", "大道", "大街", "环路", "高架", "隧道", "高速", "路"};
        int end = -1;
        int suffixLength = 0;
        for (String suffix : suffixes) {
            int idx = userText.indexOf(suffix);
            if (idx >= 0 && (end < 0 || idx < end)) {
                end = idx;
                suffixLength = suffix.length();
            }
        }
        if (end < 0) {
            return "";
        }
        String stopChars = "，。！？、 看问查说去走顺陪了没有";
        int start = Math.max(0, end - 10);
        for (int i = end - 1; i >= start; i--) {
            if (stopChars.indexOf(userText.charAt(i)) >= 0) {
                start = i + 1;
                break;
            }
        }
        String candidate = userText.substring(start, end + suffixLength).trim();
        // 至少 2 个字才算路名；候选里还混着动词/否定词，说明不是真实路名
        if (candidate.length() < 2) {
            return "";
        }
        for (char c : candidate.toCharArray()) {
            if (stopChars.indexOf(c) >= 0) {
                return "";
            }
        }
        return candidate;
    }

    static List<String> extractItemNames(String text, int limit) {
        List<String> names = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return names;
        }
        for (String line : text.split("\\R")) {
            Matcher m = NUMBERED_ITEM.matcher(line);
            if (!m.find()) {
                continue;
            }
            String item = m.group(1).trim();
            int idx = item.indexOf('，');
            if (idx > 0) {
                item = item.substring(0, idx).trim();
            }
            idx = item.indexOf('（');
            if (idx > 0) {
                item = item.substring(0, idx).trim();
            }
            if (!item.isBlank()) {
                names.add(item);
            }
            if (names.size() >= limit) {
                break;
            }
        }
        return names;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** 正则兜底解析出的行程需求 */
    static record ParsedTripRequest(String origin, String destination, int days, String style, String city) {
        static ParsedTripRequest from(String userText) {
            String origin = guessOrigin(userText);
            String destination = guessDestination(userText);
            int days = guessDays(userText);
            String style = guessStyle(userText);
            String city = guessCity(userText, origin, destination);
            return new ParsedTripRequest(origin, destination, days, style, city);
        }

        private static String guessCity(String userText, String origin, String destination) {
            if (!origin.isBlank()) {
                return origin;
            }
            if (!destination.isBlank()) {
                return destination;
            }
            return guessDestination(userText);
        }

        private static String guessDestination(String userText) {
            String[] markers = {"到", "去", "前往", "自驾到", "到达"};
            for (String marker : markers) {
                int idx = userText.indexOf(marker);
                if (idx >= 0 && idx + marker.length() < userText.length()) {
                    String tail = userText.substring(idx + marker.length()).trim();
                    String place = cleanPlace(tail);
                    if (!place.isBlank()) {
                        return place;
                    }
                }
            }
            return "";
        }

        private static String guessOrigin(String userText) {
            int idx = userText.indexOf("从");
            if (idx >= 0) {
                String tail = userText.substring(idx + 1).trim();
                int toIdx = tail.indexOf("到");
                if (toIdx > 0) {
                    String place = cleanPlace(tail.substring(0, toIdx));
                    if (!place.isBlank()) {
                        return place;
                    }
                }
            }
            return "";
        }

        private static int guessDays(String userText) {
            Matcher matcher = Pattern.compile("([\\d一二两三四五六七八九十]+)\\s*[天晚]").matcher(userText);
            if (matcher.find()) {
                try {
                    return Math.max(1, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    return Math.max(1, chineseDays(matcher.group(1)));
                }
            }
            return 3;
        }

        private static int chineseDays(String text) {
            int total = 0;
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '十' -> total = total == 0 ? 10 : total + 10;
                    case '一' -> total += 1;
                    case '二', '两' -> total += 2;
                    case '三' -> total += 3;
                    case '四' -> total += 4;
                    case '五' -> total += 5;
                    case '六' -> total += 6;
                    case '七' -> total += 7;
                    case '八' -> total += 8;
                    case '九' -> total += 9;
                    default -> {
                    }
                }
            }
            return total;
        }

        private static String guessStyle(String userText) {
            List<String> styles = List.of("轻松", "休闲", "打卡", "亲子", "摄影", "美食", "自然", "人文", "深度");
            for (String style : styles) {
                if (userText.contains(style)) {
                    return style;
                }
            }
            return "";
        }

        private static String cleanPlace(String text) {
            String cleaned = text.replaceAll("[，。！？!?；;、\\s].*$", "").trim();
            cleaned = cleaned.replaceAll("^(帮我|给我|想|需要|安排|规划)+", "");
            // 去掉尾部“玩三天”“自驾五天”这类天数尾巴
            cleaned = cleaned.replaceAll("(玩|自驾|行程|旅行|游玩)?([\\d一二两三四五六七八九十]+)\\s*[天晚].*$", "");
            return cleaned.isBlank() ? "" : cleaned;
        }
    }
}
