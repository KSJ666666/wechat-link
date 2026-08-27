package com.group.autotrip.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.group.autotrip.output.TripPlanOutput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 自驾/旅行规划 Skill：按“解析 -> 工具 -> LLM -> 成品输出”串起来。 */
@Component
public class TripPlanSkill implements Skill {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NUMBERED_ITEM = Pattern.compile("^\\s*\\d+[.、]\\s*(.+)$");

    @Override
    public String name() {
        return "trip_plan_skill";
    }

    @Override
    public String description() {
        return "处理明确的自驾旅行规划需求，例如“从杭州到黄山三天自驾，偏景点和轻松节奏”，会串联路线、景点、距离和天气工具。";
    }

    @Override
    public boolean supports(String userText) {
        if (userText == null) {
            return false;
        }
        return containsAny(userText,
                "自驾", "旅行", "旅游", "行程", "路线", "路书", "规划", "出游", "怎么玩", "怎么去",
                "几天", "几晚", "出发地", "目的地", "行程单", "顺路", "沿途", "攻略");
    }

    @Override
    public String execute(String userText, SkillContext ctx) throws Exception {
        ParsedTripRequest request = ParsedTripRequest.from(userText);
        if (request.destination().isBlank()) {
            return "我已经命中旅行规划技能，但还缺目的地。你可以直接告诉我“从哪到哪、几天、偏景点还是偏休闲”。";
        }

        List<String> sections = new ArrayList<>();
        String weatherNow = execTool(ctx, "query_weather", MAPPER.createObjectNode().put("location", request.destination()));
        if (!weatherNow.isBlank()) {
            sections.add("实时天气：\n" + weatherNow);
        }

        String forecast = execTool(ctx, "query_weather_forecast",
                MAPPER.createObjectNode()
                        .put("location", request.destination())
                        .put("days", Math.min(Math.max(request.days(), 1), 5)));
        if (!forecast.isBlank()) {
            sections.add("天气预报：\n" + forecast);
        }

        String route = "";
        if (!request.origin().isBlank()) {
            com.fasterxml.jackson.databind.node.ObjectNode routeArgs = MAPPER.createObjectNode()
                    .put("origin", request.origin())
                    .put("destination", request.destination())
                    .put("mode", "all")
                    .put("prefer", request.style().isBlank() ? "avoid_driving" : "");
            if (!request.city().isBlank()) {
                routeArgs.put("city", request.city());
            }
            route = execTool(ctx, "query_route", routeArgs);
        }
        if (!route.isBlank()) {
            sections.add("路线推荐：\n" + route);
        }

        if (shouldUseTraffic(userText)) {
            String road = guessRoad(userText);
            if (!road.isBlank()) {
                String traffic = execTool(ctx, "query_traffic",
                        MAPPER.createObjectNode()
                                .put("city", nonBlank(request.destination(), request.origin()))
                                .put("road", road));
                if (!traffic.isBlank()) {
                    sections.add("路况参考：\n" + traffic);
                }
            }
        }

        String attractions = execTool(ctx, "query_attractions",
                MAPPER.createObjectNode()
                        .put("city", request.destination())
                        .put("limit", 5));
        if (!attractions.isBlank()) {
            sections.add("景点列表：\n" + attractions);
        }

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
        if (!attractionDetails.isEmpty()) {
            sections.add("重点景点详情：\n" + String.join("\n", attractionDetails));
        }

        if (!request.origin().isBlank() && !attractionNames.isEmpty()) {
            com.fasterxml.jackson.databind.node.ObjectNode matrixArgs = MAPPER.createObjectNode()
                    .put("origin", request.origin())
                    .put("city", request.destination())
                    .put("mode", "driving");
            var arr = matrixArgs.putArray("destinations");
            for (String name : attractionNames) {
                arr.add(name);
            }
            String matrix = execTool(ctx, "query_distance_matrix", matrixArgs);
            if (!matrix.isBlank()) {
                sections.add("景点顺路排序：\n" + matrix);
            }
        }

        if (shouldUsePoiSearch(userText)) {
            String poi = execTool(ctx, "search_poi",
                    MAPPER.createObjectNode()
                            .put("keywords", request.style().isBlank() ? "酒店" : request.style())
                            .put("city", request.destination())
                            .put("limit", 5));
            if (!poi.isBlank()) {
                sections.add("周边地点：\n" + poi);
            }
        }

        String llmPlan = ctx.llm().chat(buildPlannerPrompt(userText, request, sections));

        return TripPlanOutput.render(
                request.origin(),
                request.destination(),
                request.days(),
                request.style(),
                weatherNow,
                forecast,
                route,
                attractions,
                attractionDetails,
                llmPlan,
                buildTips(request, sections));
    }

    private String buildPlannerPrompt(String userText, ParsedTripRequest request, List<String> sections) {
        StringBuilder context = new StringBuilder();
        for (String section : sections) {
            context.append(section).append("\n\n");
        }
        return """
                你是一个旅行规划助手。请根据下面的工具结果，生成一版适合直接发给用户的中文旅行路书。
                要求：
                1. 只输出成品内容，不要分析过程。
                2. 结构尽量清晰：先总览，再分天，再给执行建议。
                3. 不要编造工具里没有出现的具体景点名。
                4. 如果路线、天气、景点信息冲突，优先采用工具结果。
                5. 语言简洁自然，适合微信直接发送。

                用户原话：
                %s

                结构化信息：
                出发地：%s
                目的地：%s
                天数：%d
                偏好：%s

                工具结果：
                %s
                """.formatted(
                userText,
                nonBlank(request.origin(), "未提供"),
                nonBlank(request.destination(), "未提供"),
                request.days(),
                nonBlank(request.style(), "未提供"),
                context);
    }

    private static boolean shouldUsePoiSearch(String userText) {
        if (userText == null) {
            return false;
        }
        return userText.contains("酒店") || userText.contains("餐厅") || userText.contains("吃") || userText.contains("住");
    }

    private static boolean shouldUseTraffic(String userText) {
        if (userText == null) {
            return false;
        }
        return userText.contains("路况") || userText.contains("堵车") || userText.contains("拥堵") || userText.contains("高速");
    }

    private static String guessRoad(String userText) {
        Matcher matcher = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9]+(?:路|大道|快速路|环路|高速|高速路|高架|隧道))").matcher(userText);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    private static List<String> extractItemNames(String text, int limit) {
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

    private String execTool(SkillContext ctx, String name, JsonNode args) {
        try {
            return ctx.tools().execute(name, args);
        } catch (Exception e) {
            return "";
        }
    }

    private static List<String> buildTips(ParsedTripRequest request, List<String> sections) {
        List<String> tips = new ArrayList<>();
        tips.add("每天尽量只安排 2 到 3 个重点，给堵车、停车和临时改线留余量。");
        tips.add("先确认住宿和停车，再排景点顺序，整体会更顺。");
        if (request.origin().isBlank()) {
            tips.add("补一个出发地后，路线顺序还能再优化一次。");
        }
        if (sections.isEmpty()) {
            tips.add("工具结果不够完整时，建议补充城市、起点和偏好，我可以再重排。");
        }
        return tips;
    }

    private static boolean containsAny(String text, String... keywords) {
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

    private record ParsedTripRequest(String origin, String destination, int days, String style, String city) {
        private static ParsedTripRequest from(String userText) {
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
            Matcher matcher = Pattern.compile("(\\d+)\\s*天").matcher(userText);
            if (matcher.find()) {
                try {
                    return Math.max(1, Integer.parseInt(matcher.group(1)));
                } catch (NumberFormatException ignored) {
                    return 3;
                }
            }
            return 3;
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
            return cleaned.isBlank() ? "" : cleaned;
        }
    }
}
