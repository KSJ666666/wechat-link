package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class QueryDistanceMatrixTool implements FunctionTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AmapService amapService;
    public QueryDistanceMatrixTool(AmapService amapService) { this.amapService = amapService; }
    @Override public String name() { return "query_distance_matrix"; }
    @Override public String description() { return "一次计算一个起点到多个目的地的距离和预计耗时，用于比较哪些地点顺路。"; }
    @Override public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("origin").put("type", "string").put("description", "起点地点（必填）");
        props.putArray("destinations").addObject().put("type", "string").put("description", "目的地地点列表（必填）");
        props.putObject("city").put("type", "string").put("description", "地点所在城市（可选）");
        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string");
        mode.put("description", "driving=驾车距离（默认），straight=直线距离");
        mode.set("enum", MAPPER.createArrayNode().add("driving").add("straight"));
        p.putArray("required").add("origin").add("destinations");
        p.put("additionalProperties", false);
        return p;
    }
    @Override public String execute(JsonNode args) throws Exception {
        String origin = args.path("origin").asText("");
        String city = args.path("city").asText("");
        boolean driving = !"straight".equalsIgnoreCase(args.path("mode").asText("driving"));
        List<String> destinations = new ArrayList<>();
        JsonNode dests = args.path("destinations");
        if (dests.isArray()) for (JsonNode d : dests) { String value = d.asText(""); if (!value.isBlank()) destinations.add(value); }
        if (origin.isBlank() || destinations.isEmpty()) throw new IllegalArgumentException("origin 和 destinations 参数不能为空");
        List<AmapService.DistanceInfo> distances = amapService.getDistanceMatrix(origin, destinations, city, driving);
        StringBuilder sb = new StringBuilder("从 ").append(origin).append(" 出发");
        sb.append(driving ? "（驾车距离，由近到远）：\n" : "（直线距离，由近到远）：\n");
        int number = 1;
        for (AmapService.DistanceInfo info : distances) sb.append(number++).append(". ").append(info).append('\n');
        return sb.toString().trim();
    }
}
