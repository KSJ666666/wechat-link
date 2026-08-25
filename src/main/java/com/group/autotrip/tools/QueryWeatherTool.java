package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

/** 查询城市实时天气（注册工具，可被模型在同轮请求中多次调用）。 */
@Component
public class QueryWeatherTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WeatherService weatherService;

    public QueryWeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String name() {
        return "query_weather";
    }

    @Override
    public String description() {
        return "查询某个城市的实时天气（当前天气情况）。当用户询问某城市的当前或实时天气时调用，例如'郑州天气怎么样'、'上海现在热吗'。"
                + "仅支持具体城市，不支持省份、自治区等省级区域——用户询问省级天气时不要调用本工具，应提示用户提供具体城市名。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode loc = p.putObject("properties").putObject("location");
        loc.put("type", "string");
        loc.put("description", "地点：必须是具体城市名（如：郑州、上海）或城市拼音（如：zhengzhou），不能是省份、自治区等省级区域");
        ArrayNode req = p.putArray("required");
        req.add("location");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String location = args.path("location").asText("");
        if (location.isBlank()) {
            throw new IllegalArgumentException("location 参数不能为空");
        }
        return weatherService.getNowWeather(location).toString();
    }
}
