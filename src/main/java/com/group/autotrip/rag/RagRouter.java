package com.group.autotrip.rag;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 景点指南问题路由器：命中"四个指南城市 + 景点意图"关键词时返回城市名，未命中返回空字符串。
 *
 * <p>用于消息三级路由的第二级（Skill → RAG → LLM）：确定性把景点指南类问题导向 RAG 检索，
 * 不再依赖模型自觉调用 query_guide_rag 工具。
 */
@Component
public class RagRouter {

    private static final List<String> CITIES = List.of("大理", "杭州", "上海", "长沙");

    private static final List<String> INTENTS = List.of(
            "景点", "好玩", "攻略", "必去", "推荐", "开放时间", "门票", "介绍", "游玩", "打卡", "值得去");

    /** 命中返回城市名，未命中返回空字符串 */
    public String match(String userText) {
        if (userText == null || userText.isBlank()) {
            return "";
        }
        String city = "";
        for (String candidate : CITIES) {
            if (userText.contains(candidate)) {
                city = candidate;
                break;
            }
        }
        if (city.isEmpty()) {
            return "";
        }
        for (String intent : INTENTS) {
            if (userText.contains(intent)) {
                return city;
            }
        }
        return "";
    }
}
