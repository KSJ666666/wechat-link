package com.group.autotrip.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import org.springframework.stereotype.Component;

import java.util.List;

/** 按关键词搜索某城市的地点（餐厅、酒店、景点、商场等）。 */
@Component
public class SearchPoiTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AmapService amapService;

    public SearchPoiTool(AmapService amapService) {
        this.amapService = amapService;
    }

    @Override
    public String name() {
        return "search_poi";
    }

    @Override
    public String description() {
        return "按关键词搜索某城市的地点（如餐厅、酒店、景点、商场、医院）。"
                + "当用户询问'某城市有什么餐厅/酒店/景点/商场'、'帮我推荐附近的地方'等需要具体地点列表的问题时调用。"
                + "可用于攻略中推荐美食、住宿和周边设施。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("keywords")
                .put("type", "string")
                .put("description", "搜索关键词，例如：餐厅、酒店、西湖");
        props.putObject("city")
                .put("type", "string")
                .put("description", "城市名或拼音（可选，如：北京、shanghai），不填则全国范围搜索");
        props.putObject("limit")
                .put("type", "integer")
                .put("description", "最多返回条数（可选，默认 5，最大 10）");
        p.putArray("required").add("keywords");
        p.put("additionalProperties", false);
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String keywords = args.path("keywords").asText("");
        String city = args.path("city").asText("");
        int limit = args.path("limit").asInt(5);
        if (keywords.isBlank()) {
            throw new IllegalArgumentException("keywords 参数不能为空");
        }

        List<AmapService.PoiInfo> pois = amapService.searchPoi(keywords, null, city, limit);
        if (pois.isEmpty()) {
            return "未找到相关地点，请尝试更换关键词或补充城市名。";
        }
        StringBuilder sb = new StringBuilder("找到 ").append(pois.size()).append(" 个地点：\n");
        for (int i = 0; i < pois.size(); i++) {
            sb.append(i + 1).append(". ").append(pois.get(i)).append('\n');
        }
        return sb.toString().trim();
    }
}
