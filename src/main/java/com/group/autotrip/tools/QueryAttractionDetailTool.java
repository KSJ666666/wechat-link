package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

@Component
public class QueryAttractionDetailTool implements FunctionTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AmapService amapService;
    public QueryAttractionDetailTool(AmapService amapService) { this.amapService = amapService; }
    @Override public String name() { return "query_attraction_detail"; }
    @Override public String description() { return "查询单个景点的详细信息（评分、景区等级、开放时间、电话、地址）。"; }
    @Override public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("city").put("type", "string").put("description", "景点所在城市（必填）");
        props.putObject("name").put("type", "string").put("description", "景点名称（必填）");
        p.putArray("required").add("city").add("name");
        p.put("additionalProperties", false);
        return p;
    }
    @Override public String execute(JsonNode args) throws Exception {
        String city = args.path("city").asText("");
        String name = args.path("name").asText("");
        if (city.isBlank() || name.isBlank()) throw new IllegalArgumentException("city 和 name 参数不能为空");
        return amapService.getAttractionDetail(city, name).toString();
    }
}
