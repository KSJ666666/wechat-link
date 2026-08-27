package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryAttractionsTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AmapService amapService;

    public QueryAttractionsTool(AmapService amapService) { this.amapService = amapService; }

    @Override public String name() { return "query_attractions"; }

    @Override
    public String description() {
        return "查询某城市的景点/景区列表。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("city").put("type", "string").put("description", "城市名或拼音（必填）");
        props.putObject("keyword").put("type", "string").put("description", "具体景点名（可选）");
        props.putObject("limit").put("type", "integer").put("description", "最多返回条数（可选，默认 5，最大 10）");
        p.putArray("required").add("city");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String city = args.path("city").asText("");
        String keyword = args.path("keyword").asText("");
        int limit = args.path("limit").asInt(5);
        if (city.isBlank()) throw new IllegalArgumentException("city 参数不能为空");
        List<AmapService.PoiInfo> pois = amapService.searchPoi(keyword, "风景名胜", city, limit);
        if (pois.isEmpty()) return "未找到相关景点，请尝试更换城市或景点名称。";
        StringBuilder sb = new StringBuilder("找到 ").append(pois.size()).append(" 个景点：\n");
        for (int i = 0; i < pois.size(); i++) sb.append(i + 1).append(". ").append(pois.get(i)).append('\n');
        return sb.toString().trim();
    }
}
