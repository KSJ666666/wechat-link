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
    private final CityTransportSupport citySupport;

    public QueryTrafficTool(AmapService amapService, CityTransportSupport citySupport) {
        this.amapService = amapService;
        this.citySupport = citySupport;
    }

    @Override
    public String name() {
        return "query_traffic";
    }

    @Override
    public String description() {
        return "查询某条道路的实时交通路况（是否拥堵）。"
                + "当用户询问'XX路堵不堵''路况怎么样''会不会堵车'时调用，需要提供城市和道路名称。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("city")
                .put("type", "string")
                .put("description", "城市名（必填，如：北京、长沙、深圳）");
        props.putObject("road")
                .put("type", "string")
                .put("description", "道路名称（必填，如：北四环中路、京港澳高速、陇海快速路）");
        p.putArray("required").add("city").add("road");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String city = args.path("city").asText("");
        String road = args.path("road").asText("");
        if (city.isBlank() || road.isBlank()) {
            throw new IllegalArgumentException("city 和 road 参数不能为空");
        }
        String cityCode = citySupport.cityCode(city);
        if (cityCode.isBlank()) {
            cityCode = city;
        }
        return city + " " + road + "：" + amapService.getRoadTraffic(cityCode, road, "5");
    }
}
