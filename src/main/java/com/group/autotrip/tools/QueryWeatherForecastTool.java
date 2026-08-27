package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QueryWeatherForecastTool implements FunctionTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WeatherService weatherService;
    public QueryWeatherForecastTool(WeatherService weatherService) { this.weatherService = weatherService; }
    @Override public String name() { return "query_weather_forecast"; }
    @Override public String description() { return "查询某个城市未来几天的天气预报（默认未来 3 天）。"; }
    @Override public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("location").put("type", "string").put("description", "必须是具体城市名或城市拼音");
        props.putObject("days").put("type", "integer").put("description", "预报天数（可选，默认 3，范围 1-15）");
        p.putArray("required").add("location");
        p.put("additionalProperties", false);
        return p;
    }
    @Override public String execute(JsonNode args) throws Exception {
        String location = args.path("location").asText("");
        int days = Math.min(Math.max(args.path("days").asInt(3), 1), 15);
        if (location.isBlank()) throw new IllegalArgumentException("location 参数不能为空");
        List<WeatherService.DailyForecast> forecasts = weatherService.getDailyWeather(location, days);
        if (forecasts.isEmpty()) throw new IllegalStateException("天气预报接口返回为空");
        StringBuilder sb = new StringBuilder(location).append("未来 ").append(forecasts.size()).append(" 天天气：\n");
        for (int i = 0; i < forecasts.size(); i++) sb.append(i + 1).append(". ").append(forecasts.get(i)).append('\n');
        return sb.toString().trim();
    }
}
