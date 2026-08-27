package com.group.autotrip.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.group.autotrip.common.FunctionTool;
import com.group.autotrip.rag.model.RagAnswer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库工具：注册进模型工具列表后，微信端询问大理/杭州/上海/长沙的景点指南时由模型自动调用。
 *
 * <p>通过 {@link ObjectProvider} 懒注入 RagService，解开
 * CustomTools → 本工具 → RagService → DashScopeService → CustomTools 的构造循环。
 */
@Component
public class RagKnowledgeTool implements FunctionTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectProvider<RagService> ragServiceProvider;

    public RagKnowledgeTool(ObjectProvider<RagService> ragServiceProvider) {
        this.ragServiceProvider = ragServiceProvider;
    }

    @Override
    public String name() {
        return "query_guide_rag";
    }

    @Override
    public String description() {
        return "查询大理、杭州、上海、长沙的城市景点指南（本地知识库检索，含景点介绍、开放时间、门票、地址、评分等）。"
                + "当用户询问这几个城市的景点推荐、某个景点的详情、游玩攻略、开放时间或门票信息时调用。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        props.putObject("query")
                .put("type", "string")
                .put("description", "用户的景点问题或需求，如“杭州有哪些必去景点”“大理古城开放时间”");
        props.putObject("city")
                .put("type", "string")
                .put("description", "城市（可选）：大理、杭州、上海、长沙；用户问题中提到城市时填写");
        params.putArray("required").add("query");
        params.put("additionalProperties", false);
        return params;
    }

    @Override
    public String execute(JsonNode args) {
        String query = args.path("query").asText("");
        String city = args.path("city").asText("");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query 参数不能为空");
        }
        RagAnswer answer = ragServiceProvider.getObject().ask(query, city);
        if (answer.sources().isEmpty() || answer.sourceTitles().isEmpty()) {
            return answer.answer();
        }
        return answer.answer() + "\n\n参考来源：" + answer.sourceTitles();
    }
}
