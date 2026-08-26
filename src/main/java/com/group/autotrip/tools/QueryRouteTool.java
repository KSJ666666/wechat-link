package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

/** 计算两个地点之间的驾车路线。 */
@Component
public class QueryRouteTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AmapService amapService;

    public QueryRouteTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() {
        return "query_route";
    }

    @Override
    public String description() {
        return "计算两个地点之间的驾车路线（距离、预计耗时、过路费），用于串联行程和安排一日游路线。"
                + "当用户需要知道'从A到B怎么走、多远、开车多久'、要规划景点先后顺序时调用。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("origin")
                .put("type", "string")
                .put("description", "起点地点（必填，如：北京天安门、杭州西湖）");
        props.putObject("destination")
                .put("type", "string")
                .put("description", "终点地点（必填，如：北京故宫博物院、杭州灵隐寺）");
        props.putObject("city")
                .put("type", "string")
                .put("description", "两个地点所在城市（可选，用于准确定位，如：北京）");
        p.putArray("required").add("origin").add("destination");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String origin = args.path("origin").asText("");
        String destination = args.path("destination").asText("");
        String city = args.path("city").asText("");
        if (origin.isBlank() || destination.isBlank()) {
            throw new IllegalArgumentException("origin 和 destination 参数不能为空");
        }
        return amapService.getDrivingRoute(origin, destination, city).toString();
    }
}
