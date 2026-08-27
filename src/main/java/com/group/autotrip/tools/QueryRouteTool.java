package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import com.group.autotrip.common.model.RouteOption;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class QueryRouteTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final TransportRecommender transportRecommender;

    public QueryRouteTool(TransportRecommender transportRecommender) {
        this.transportRecommender = transportRecommender;
    }

    @Override public String name() { return "query_route"; }

    @Override
    public String description() {
        return "查询两个地点之间的交通方式和出行推荐（步行、公交、地铁、驾车、高铁/火车）。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("origin").put("type", "string").put("description", "起点地点（必填）");
        props.putObject("destination").put("type", "string").put("description", "终点地点（必填）");
        props.putObject("city").put("type", "string").put("description", "两个地点所在城市（可选）");
        props.putObject("originCity").put("type", "string").put("description", "起点所在城市（可选）");
        props.putObject("destinationCity").put("type", "string").put("description", "终点所在城市（可选）");
        props.putObject("mode").put("type", "string").put("description", "交通方式（可选）：all/walking/bus/metro/driving/rail");
        props.putObject("prefer").put("type", "string").put("description", "用户偏好（可选）");
        p.putArray("required").add("origin").add("destination");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String origin = args.path("origin").asText("");
        String destination = args.path("destination").asText("");
        String city = args.path("city").asText("");
        String originCity = args.path("originCity").asText("");
        String destinationCity = args.path("destinationCity").asText("");
        String mode = args.path("mode").asText("");
        String prefer = args.path("prefer").asText("");
        if (origin.isBlank() || destination.isBlank()) {
            throw new IllegalArgumentException("origin 和 destination 参数不能为空");
        }
        if (originCity.isBlank()) originCity = city;
        if (destinationCity.isBlank()) destinationCity = city;
        return format(transportRecommender.recommend(origin, destination, originCity, destinationCity, mode, prefer));
    }

    private String format(TransportRecommender.Recommendation recommendation) {
        StringBuilder sb = new StringBuilder();
        sb.append("从 ").append(recommendation.originName()).append(" → ").append(recommendation.destinationName())
                .append("（").append(recommendation.sameCity() ? "同城" : "跨城");
        if (recommendation.baselineDistanceMeters() > 0) {
            sb.append("，约 ").append(formatKm(recommendation.baselineDistanceMeters())).append(" 公里");
        }
        sb.append("）\n");
        sb.append("推荐：").append(formatOption(recommendation.recommended()));
        if (!recommendation.reason().isBlank()) sb.append("\n推荐理由：").append(recommendation.reason());
        if (!recommendation.alternatives().isEmpty()) {
            sb.append("\n备选：");
            for (int i = 0; i < recommendation.alternatives().size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(formatOption(recommendation.alternatives().get(i)));
            }
        }
        return sb.toString();
    }

    private String formatOption(RouteOption option) {
        String summary = option.summary().isBlank() ? option.mode().displayName() : option.summary();
        StringBuilder sb = new StringBuilder(summary);
        sb.append("（约 ").append((int) Math.ceil(option.durationSeconds() / 60.0)).append(" 分钟");
        if (!option.cost().isBlank() && !"0".equals(option.cost())) sb.append("，费用 ").append(option.cost()).append(" 元");
        sb.append("）");
        return sb.toString();
    }

    private static String formatKm(long meters) {
        double km = meters / 1000.0;
        return km < 10 ? String.format(Locale.ROOT, "%.1f", km) : String.valueOf(Math.round(km));
    }
}
