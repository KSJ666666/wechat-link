package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

/** 查询某城市某条道路的实时交通路况。 */
@Component
public class QueryTrafficTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AmapService amapService;

    public QueryTrafficTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() {
        return "query_traffic";
    }

    @Override
    public String description() {
        return "查询某城市某条道路的实时交通路况（是否拥堵）。"
                + "当用户询问'某条路堵不堵''路况怎么样''会不会堵车'时调用，需要提供城市和道路名称。"
                + "可与 query_route 配合，实时路况作为路线安排参考。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("city")
                .put("type", "string")
                .put("description", "城市名或拼音（必填，如：郑州、beijing）");
        props.putObject("road")
                .put("type", "string")
                .put("description", "道路名称（必填，如：金水路、北四环中路）");
        props.putObject("level")
                .put("type", "integer")
                .put("description", "道路等级（可选）：1=高速，2=城市快速路/国道，3=高速辅路，4=主要道路，5=一般道路，6=无名道路");
        p.putArray("required").add("city").add("road");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String city = args.path("city").asText("");
        String road = args.path("road").asText("");
        String level = args.path("level").asText("");
        if (city.isBlank() || road.isBlank()) {
            throw new IllegalArgumentException("city 和 road 参数不能为空");
        }
        return city + " " + road + "：" + amapService.getRoadTraffic(city, road, level);
    }
}
